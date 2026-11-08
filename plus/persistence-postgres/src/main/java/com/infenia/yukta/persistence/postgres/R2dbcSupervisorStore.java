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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Supervisors and attachments in PostgreSQL (tables {@code supervisor} and {@code
 * supervisor_attachment}, migrations V7, V8 and V10), so supervisors attached at run time come back
 * after a restart. Settings are stored as given, a model API key included.
 */
@Slf4j
@SuppressWarnings("PMD.TooManyMethods")
public class R2dbcSupervisorStore implements SupervisorStore {

  /** The stored settings of a profile. */
  private static final TypeReference<Map<String, Object>> SETTINGS = new TypeReference<>() {};

  /** The stored refs of a session. */
  private static final TypeReference<List<SupervisorRef>> REFS = new TypeReference<>() {};

  private static final TypeReference<List<String>> PARENTS = new TypeReference<>() {};

  private static final TypeReference<Map<String, Map<String, Object>>> DELEGATION =
      new TypeReference<>() {};

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

  /** Warns once at start-up about every supervisor id that migration V8 renamed. */
  @EventListener(ApplicationReadyEvent.class)
  public void warnRenamedIds() {
    renamedIdsWarning().subscribe();
  }

  /** Logs a warning per renamed id; a store that cannot be read is logged, never an error. */
  /* default */ Mono<Void> renamedIdsWarning() {
    return client
        .sql("SELECT kind, old_id, new_id FROM supervisor_id_migration ORDER BY kind, old_id")
        .map(
            row ->
                new String[] {
                  row.get("kind", String.class),
                  row.get("old_id", String.class),
                  row.get("new_id", String.class)
                })
        .all()
        .doOnNext(
            renamed ->
                log.atWarn()
                    .addKeyValue("kind", renamed[0])
                    .addKeyValue("oldId", renamed[1])
                    .addKeyValue("newId", renamed[2])
                    .log("A supervisor id broke the naming rule and was renamed by migration V8"))
        .then()
        .onErrorResume(
            error -> {
              log.atWarn().setCause(error).log("Could not read the renamed supervisor ids");
              return Mono.empty();
            });
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
                    row.get("type", String.class),
                    mapper.readValue(row.get("settings", String.class), SETTINGS),
                    Boolean.TRUE.equals(row.get("dry_run", Boolean.class)),
                    row.get("description", String.class),
                    mapper.readValue(row.get("parents", String.class), PARENTS),
                    delegationOf(
                        mapper.readValue(row.get("delegation", String.class), DELEGATION))))
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
                + " origin_workflow_id, conflict FROM supervisor_attachment ORDER BY id")
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
                    Boolean.TRUE.equals(row.get("conflict", Boolean.class))))
        .all();
  }

  @Override
  public Mono<Void> saveAttachment(final SupervisorAttachment binding) {
    return client
        .sql(
            """
            INSERT INTO supervisor_attachment (id, supervisor_id, scope, enabled, source,
              origin_session_id, origin_workflow_id, conflict, updated_at)
            VALUES (:id, :supervisor, :scope, :enabled, :source, :originSession,
              :originWorkflow, :conflict, now())
            ON CONFLICT (id) DO UPDATE SET supervisor_id = EXCLUDED.supervisor_id,
              scope = EXCLUDED.scope, enabled = EXCLUDED.enabled, source = EXCLUDED.source,
              origin_session_id = EXCLUDED.origin_session_id,
              origin_workflow_id = EXCLUDED.origin_workflow_id, conflict = EXCLUDED.conflict,
              updated_at = now()
            """)
        .bind("id", binding.id())
        .bind("supervisor", binding.supervisorId())
        .bind("scope", binding.scope().key())
        .bind("enabled", binding.enabled())
        .bind("source", binding.source().name())
        .bind("originSession", nullable(binding.originSessionId()))
        .bind("originWorkflow", nullable(binding.originWorkflowId()))
        .bind("conflict", binding.conflict())
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

  /** A text parameter that may be null, which R2DBC wants typed. */
  private static Object nullable(final String value) {
    return value == null ? io.r2dbc.spi.Parameters.in(String.class) : value;
  }
}
