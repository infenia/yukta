// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.plugin.control.Delegation;
import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.DelegationMode;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.supervisor.SupervisorStore;
import com.infenia.yukta.service.supervisor.SupervisorVersions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Supervisors and attachments in PostgreSQL (tables {@code supervisor} and {@code
 * supervisor_attachment}, V1; versions in {@code supervisor_version}), so supervisors attached at
 * run time come back after a restart. Settings are stored as given, a model API key included.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
public class R2dbcSupervisorStore implements SupervisorStore {

  /** The stored settings of a profile. */
  private static final TypeReference<Map<String, Object>> SETTINGS = new TypeReference<>() {};

  /** The stored refs of a session. */
  private static final TypeReference<List<SupervisorRef>> REFS = new TypeReference<>() {};

  private static final TypeReference<List<String>> PARENTS = new TypeReference<>() {};

  private static final TypeReference<Map<String, Map<String, Object>>> DELEGATION =
      new TypeReference<>() {};

  // column names, also the keys of a version body
  private static final String TYPE = "type";
  private static final String SETTINGS_FIELD = "settings";
  private static final String DESCRIPTION = "description";
  private static final String PARENTS_FIELD = "parents";
  private static final String DELEGATION_FIELD = "delegation";

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the settings as JSON. */
  private final ObjectMapper mapper;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   * @param mapper reads and writes the settings
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public R2dbcSupervisorStore(final DatabaseClient client, final ObjectMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public Flux<Supervisor> supervisors() {
    return client
        .sql(
            "SELECT id, type, CAST(settings AS text) AS settings, dry_run, description,"
                + " CAST(parents AS text) AS parents, CAST(delegation AS text) AS delegation"
                + " FROM supervisor ORDER BY id")
        .map(
            row ->
                new Supervisor(
                    row.get("id", String.class),
                    row.get(TYPE, String.class),
                    mapper.readValue(row.get(SETTINGS_FIELD, String.class), SETTINGS),
                    Boolean.TRUE.equals(row.get("dry_run", Boolean.class)),
                    row.get(DESCRIPTION, String.class),
                    mapper.readValue(row.get(PARENTS_FIELD, String.class), PARENTS),
                    delegationOf(
                        mapper.readValue(row.get(DELEGATION_FIELD, String.class), DELEGATION))))
        .all();
  }

  @Override
  public Mono<Void> saveSupervisor(final Supervisor profile) {
    return Mono.fromCallable(
            () ->
                new String[] {
                  mapper.writeValueAsString(profile.settings()),
                  mapper.writeValueAsString(profile.parents()),
                  mapper.writeValueAsString(delegationJson(profile.delegation()))
                })
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO supervisor (id, type, settings, dry_run, description, parents,
                          delegation, updated_at)
                        VALUES (:id, :type, CAST(:settings AS jsonb), :dry, :description,
                          CAST(:parents AS jsonb), CAST(:delegation AS jsonb), now())
                        ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type,
                          settings = EXCLUDED.settings, dry_run = EXCLUDED.dry_run,
                          description = EXCLUDED.description, parents = EXCLUDED.parents,
                          delegation = EXCLUDED.delegation, updated_at = now()
                        """)
                    .bind("id", profile.id())
                    .bind("type", profile.type())
                    .bind("settings", json[0])
                    .bind("parents", json[1])
                    .bind("delegation", json[2])
                    .bind("dry", profile.dryRun())
                    .bind("description", nullable(profile.description()))
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  /** The delegation modes as JSON: kind to mode and timeout in milliseconds. */
  private static Map<String, Map<String, Object>> delegationJson(
      final Map<DelegationKind, Delegation> delegation) {
    final Map<String, Map<String, Object>> json = new java.util.TreeMap<>();
    delegation.forEach((kind, d) -> json.put(kind.name(), delegationEntry(d)));
    return json;
  }

  private static Map<String, Object> delegationEntry(final Delegation d) {
    return Map.of("mode", d.mode().name(), "timeoutMs", d.timeout().toMillis());
  }

  private static Map<DelegationKind, Delegation> delegationOf(
      final Map<String, Map<String, Object>> json) {
    final Map<DelegationKind, Delegation> delegation =
        new java.util.EnumMap<>(DelegationKind.class);
    json.forEach((kind, d) -> delegation.put(DelegationKind.valueOf(kind), delegationFrom(d)));
    return delegation;
  }

  private static Delegation delegationFrom(final Map<String, Object> d) {
    return new Delegation(
        DelegationMode.valueOf(String.valueOf(d.get("mode"))),
        java.time.Duration.ofMillis(((Number) d.get("timeoutMs")).longValue()));
  }

  @Override
  public Mono<Boolean> deleteSupervisor(final String id) {
    return client
        .sql("DELETE FROM supervisor WHERE id = :id")
        .bind("id", id)
        .fetch()
        .rowsUpdated()
        .map(rows -> rows > 0);
  }

  @Override
  public Flux<SupervisorAttachment> attachments() {
    return client
        .sql(
            "SELECT id, supervisor_id, scope, enabled, source, origin_session_id,"
                + " origin_workflow_id, conflict, locked FROM supervisor_attachment ORDER BY id")
        .map(
            row ->
                new SupervisorAttachment(
                    row.get("id", String.class),
                    row.get("supervisor_id", String.class),
                    SupervisorScope.parse(row.get("scope", String.class)),
                    Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                    SupervisorSource.valueOf(row.get("source", String.class)),
                    row.get("origin_session_id", String.class),
                    row.get("origin_workflow_id", String.class),
                    Boolean.TRUE.equals(row.get("conflict", Boolean.class)),
                    Boolean.TRUE.equals(row.get("locked", Boolean.class))))
        .all();
  }

  @Override
  public Mono<Void> saveAttachment(final SupervisorAttachment binding) {
    return client
        .sql(
            """
            INSERT INTO supervisor_attachment (id, supervisor_id, scope, enabled, source,
              origin_session_id, origin_workflow_id, conflict, locked, updated_at)
            VALUES (:id, :supervisor, :scope, :enabled, :source, :originSession,
              :originWorkflow, :conflict, :locked, now())
            ON CONFLICT (id) DO UPDATE SET supervisor_id = EXCLUDED.supervisor_id,
              scope = EXCLUDED.scope, enabled = EXCLUDED.enabled, source = EXCLUDED.source,
              origin_session_id = EXCLUDED.origin_session_id,
              origin_workflow_id = EXCLUDED.origin_workflow_id, conflict = EXCLUDED.conflict,
              locked = EXCLUDED.locked, updated_at = now()
            """)
        .bind("id", binding.id())
        .bind("supervisor", binding.supervisorId())
        .bind("scope", binding.scope().key())
        .bind("enabled", binding.enabled())
        .bind("source", binding.source().name())
        .bind("originSession", nullable(binding.originSessionId()))
        .bind("originWorkflow", nullable(binding.originWorkflowId()))
        .bind("conflict", binding.conflict())
        .bind("locked", binding.locked())
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Mono<Boolean> deleteAttachment(final String id) {
    return client
        .sql("DELETE FROM supervisor_attachment WHERE id = :id")
        .bind("id", id)
        .fetch()
        .rowsUpdated()
        .map(rows -> rows > 0);
  }

  @Override
  public Mono<List<SupervisorRef>> sessionRefs(final String sessionId) {
    return client
        .sql("SELECT CAST(refs AS text) AS refs FROM session_supervisor WHERE session_id = :id")
        .bind("id", sessionId)
        .map(row -> mapper.readValue(row.get("refs", String.class), REFS))
        .one()
        .defaultIfEmpty(List.of());
  }

  @Override
  public Mono<Void> saveSessionRefs(final String sessionId, final List<SupervisorRef> refs) {
    if (refs.isEmpty()) {
      return client
          .sql("DELETE FROM session_supervisor WHERE session_id = :id")
          .bind("id", sessionId)
          .fetch()
          .rowsUpdated()
          .then();
    }
    return Mono.fromCallable(() -> mapper.writeValueAsString(refs))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO session_supervisor (session_id, refs, updated_at)
                        VALUES (:id, CAST(:refs AS jsonb), now())
                        ON CONFLICT (session_id) DO UPDATE SET refs = EXCLUDED.refs,
                          updated_at = now()
                        """)
                    .bind("id", sessionId)
                    .bind("refs", json)
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  @Override
  public Mono<Void> saveVersion(
      final Supervisor supervisor, final Instant savedAt, final int keep) {
    // the number is the newest plus one, and nothing is added when the body equals the newest
    // (jsonb equality, so 1 and 1.0 or a reordered map are the same); two saves of one supervisor
    // at once can take the same number, so the loser tries again
    return Mono.fromCallable(() -> mapper.writeValueAsString(body(supervisor)))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO supervisor_version (supervisor_id, version, saved_at, body)
                        SELECT :id, COALESCE(MAX(v.version), 0) + 1, :at, CAST(:body AS jsonb)
                        FROM supervisor_version v WHERE v.supervisor_id = :id
                        HAVING COALESCE((SELECT n.body <> CAST(:body AS jsonb)
                          FROM supervisor_version n WHERE n.supervisor_id = :id
                          ORDER BY n.version DESC LIMIT 1), true)
                        """)
                    .bind("id", supervisor.id())
                    .bind("at", savedAt)
                    .bind("body", json)
                    .fetch()
                    .rowsUpdated())
        .retryWhen(Retry.max(3).filter(DataIntegrityViolationException.class::isInstance))
        .then(
            client
                .sql(
                    """
                    DELETE FROM supervisor_version WHERE supervisor_id = :id
                    AND version <= (SELECT MAX(version) FROM supervisor_version
                      WHERE supervisor_id = :id) - :keep
                    """)
                .bind("id", supervisor.id())
                .bind("keep", keep)
                .fetch()
                .rowsUpdated())
        .then();
  }

  /** A supervisor as one JSON document, in the same shape as the columns of its own row. */
  private static Map<String, Object> body(final Supervisor supervisor) {
    final Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put(TYPE, supervisor.type());
    body.put(SETTINGS_FIELD, supervisor.settings());
    body.put("dryRun", supervisor.dryRun());
    body.put(DESCRIPTION, supervisor.description());
    body.put(PARENTS_FIELD, supervisor.parents());
    body.put(DELEGATION_FIELD, delegationJson(supervisor.delegation()));
    return body;
  }

  private Supervisor fromBody(final String id, final Map<String, Object> body) {
    return new Supervisor(
        id,
        String.valueOf(body.get(TYPE)),
        mapper.convertValue(body.get(SETTINGS_FIELD), SETTINGS),
        Boolean.TRUE.equals(body.get("dryRun")),
        (String) body.get(DESCRIPTION),
        mapper.convertValue(body.get(PARENTS_FIELD), PARENTS),
        delegationOf(mapper.convertValue(body.get(DELEGATION_FIELD), DELEGATION)));
  }

  @Override
  public Flux<SupervisorVersions.Version> versions(final String id) {
    return client
        .sql(
            "SELECT version, saved_at, CAST(body AS text) AS body FROM supervisor_version"
                + " WHERE supervisor_id = :id ORDER BY version DESC")
        .bind("id", id)
        .map(
            row ->
                new SupervisorVersions.Version(
                    Objects.requireNonNull(row.get("version", Integer.class)),
                    row.get("saved_at", Instant.class),
                    fromBody(id, mapper.readValue(row.get("body", String.class), SETTINGS))))
        .all();
  }

  @Override
  public Mono<Void> deleteVersions(final String id) {
    return client
        .sql("DELETE FROM supervisor_version WHERE supervisor_id = :id")
        .bind("id", id)
        .fetch()
        .rowsUpdated()
        .then();
  }

  /** A text parameter that may be null, which R2DBC wants typed. */
  private static Object nullable(final String value) {
    return value == null ? io.r2dbc.spi.Parameters.in(String.class) : value;
  }
}
