// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps session configuration in PostgreSQL, with the same rules as the other stores: an apply
 * replaces every value but the initiated time, which is kept from the first apply; {@link
 * #setInitiator} sets the initiator only while it is empty.
 */
@RequiredArgsConstructor
@SuppressWarnings("PMD.LinguisticNaming")
public class R2dbcSessionConfigStore implements SessionConfigStore {

  /** Tags as stored. */
  private static final TypeReference<Map<String, String>> TAGS = new TypeReference<>() {};

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the tags as JSON. */
  private final ObjectMapper mapper;

  /** Where the workflows of a session are kept. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  @Override
  public Mono<Void> applySessionConfig(final SessionConfigData data) {
    return Transactions.inTransaction(client, apply(data));
  }

  /** The session row and every workflow, written in one transaction by the caller. */
  private Mono<Void> apply(final SessionConfigData data) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(data.tags()))
        .flatMap(tags ->
            client
                .sql(
                    """
                    INSERT INTO session_config
                      (session_id, project_path, initiator, initiated_time, description, name, tags)
                    VALUES (:s, :p, :i, :t, :d, :n, CAST(:g AS jsonb))
                    ON CONFLICT (session_id) DO UPDATE SET
                      project_path = EXCLUDED.project_path, initiator = EXCLUDED.initiator,
                      initiated_time = CASE WHEN session_config.initiated_time = ''
                        THEN EXCLUDED.initiated_time ELSE session_config.initiated_time END,
                      description = EXCLUDED.description, name = EXCLUDED.name,
                      tags = EXCLUDED.tags
                    """)
                .bind("s", data.sessionId())
                .bind("p", orEmpty(data.projectPath()))
                .bind("i", orEmpty(data.initiator()))
                .bind("t", Instant.now().toString())
                .bind("d", orEmpty(data.description()))
                .bind("n", orEmpty(data.name()))
                .bind("g", tags)
                .fetch()
                .rowsUpdated())
        .thenMany(Flux.fromIterable(data.workflows().values()))
        .concatMap(def -> workflowDefinitionStore.save(data.sessionId(), def))
        .then();
  }

  @Override
  public Mono<String> getProjectPath(final String sessionId) {
    return column(sessionId, "project_path");
  }

  @Override
  public Mono<String> getDescription(final String sessionId) {
    return column(sessionId, "description");
  }

  @Override
  public Mono<String> getInitiator(final String sessionId) {
    return column(sessionId, "initiator");
  }

  @Override
  public Mono<Void> setInitiator(final String sessionId, final String initiator) {
    return setIfEmpty(sessionId, "initiator", initiator);
  }

  @Override
  public Mono<String> getInitiatedTime(final String sessionId) {
    return column(sessionId, "initiated_time");
  }

  @Override
  public Mono<Map<String, String>> getTags(final String sessionId) {
    return client
        .sql("SELECT CAST(tags AS text) AS tags FROM session_config WHERE session_id = :s")
        .bind("s", sessionId)
        .map(row -> row.get("tags", String.class))
        .one()
        .map(json -> mapper.readValue(json, TAGS))
        .defaultIfEmpty(Map.of());
  }

  /**
   * The session row in one query, with its workflows. A session that has workflows but no row (a
   * delete that stopped half way) is returned with empty values, so it can be seen and deleted.
   */
  @Override
  public Mono<SessionSnapshot> getAllConfigs(final String sessionId) {
    final Mono<Function<Map<String, WorkflowDefinition>, SessionSnapshot>> row =
        client
            .sql(
                "SELECT name, description, initiator, initiated_time, CAST(tags AS text) AS tags,"
                    + " project_path FROM session_config WHERE session_id = :s")
            .bind("s", sessionId)
            .map(r -> {
              final String name = r.get("name", String.class);
              final String description = r.get("description", String.class);
              final String initiator = r.get("initiator", String.class);
              final String initiated = r.get("initiated_time", String.class);
              final Map<String, String> tags = mapper.readValue(r.get("tags", String.class), TAGS);
              final String projectPath = r.get("project_path", String.class);
              return (Function<Map<String, WorkflowDefinition>, SessionSnapshot>)
                  workflows ->
                      new SessionSnapshot(
                          sessionId,
                          name,
                          description,
                          initiator,
                          initiated,
                          tags,
                          projectPath,
                          workflows);
            })
            .one();
    // workflows without a row (a delete that stopped half way) still show, so they can be deleted
    return workflowDefinitionStore
        .findAll(sessionId)
        .flatMap(workflows ->
            row.switchIfEmpty(
                    workflows.isEmpty()
                        ? Mono.empty()
                        : Mono.just(
                            w -> new SessionSnapshot(sessionId, "", "", "", "", Map.of(), "", w)))
                .map(snapshot -> snapshot.apply(workflows)));
  }

  @Override
  public Mono<Void> removeSession(final String sessionId) {
    return client
        .sql("DELETE FROM session_config WHERE session_id = :s")
        .bind("s", sessionId)
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Flux<String> getSessionIds() {
    return client
        .sql(
            "SELECT session_id FROM session_config"
                + " UNION SELECT session_id FROM workflow_definition")
        .map(row -> row.get("session_id", String.class))
        .all();
  }

  /** The text column of a session, empty if the session is unknown. */
  private Mono<String> column(final String sessionId, final String column) {
    return client
        .sql("SELECT " + column + " AS value FROM session_config WHERE session_id = :s")
        .bind("s", sessionId)
        .map(row -> row.get("value", String.class))
        .one()
        .defaultIfEmpty("");
  }

  /** Sets a text column while it is empty; creates the session if it is new. */
  private Mono<Void> setIfEmpty(final String sessionId, final String column, final String value) {
    if (value == null) {
      return Mono.empty();
    }
    return client
        .sql(
            "INSERT INTO session_config (session_id, "
                + column
                + ") VALUES (:s, :v) ON CONFLICT (session_id) DO UPDATE SET "
                + column
                + " = EXCLUDED."
                + column
                + " WHERE session_config."
                + column
                + " = ''")
        .bind("s", sessionId)
        .bind("v", value)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private static String orEmpty(final String value) {
    return value == null ? "" : value;
  }
}
