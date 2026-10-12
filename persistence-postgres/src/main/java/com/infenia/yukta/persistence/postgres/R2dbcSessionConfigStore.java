// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps session configuration in PostgreSQL, with the same rules as the other stores: the project
 * path can always be changed, while description, initiator, initiated time and tags are only set
 * while they are still empty.
 */
@RequiredArgsConstructor
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.OnlyOneReturn", "PMD.LinguisticNaming"})
public class R2dbcSessionConfigStore implements SessionConfigStore {

  /** Tags as stored. */
  private static final TypeReference<Map<String, String>> TAGS = new TypeReference<>() {};

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the tags as JSON. */
  private final ObjectMapper mapper;

  /** Execution timeout and directories. */
  private final SessionConfigProperties props;

  /** Where the workflows of a session are kept. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  @Override
  public Mono<Void> applySessionConfig(final SessionConfigData data) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(data.tags()))
        .flatMap(
            tags ->
                client
                    .sql(
                        """
                        INSERT INTO session_config
                          (session_id, project_path, initiator, initiated_time, description, name, tags)
                        VALUES (:s, :p, :i, :t, :d, :n, CAST(:g AS jsonb))
                        ON CONFLICT (session_id) DO UPDATE SET
                          project_path = EXCLUDED.project_path, initiator = EXCLUDED.initiator,
                          initiated_time = EXCLUDED.initiated_time,
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
  public Mono<Void> setProjectPath(final String sessionId, final String path) {
    return client
        .sql(
            """
            INSERT INTO session_config (session_id, project_path) VALUES (:s, :v)
            ON CONFLICT (session_id) DO UPDATE SET project_path = EXCLUDED.project_path
            """)
        .bind("s", sessionId)
        .bind("v", path)
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Mono<Long> getExecutionTimeout(final String sessionId) {
    return Mono.just(props.getExecutionTimeoutSeconds());
  }

  @Override
  public Mono<String> getFileLogDir(final String sessionId) {
    return Mono.just(props.getBaseDir() + "/" + props.getFileLogSubDir());
  }

  @Override
  public Mono<String> getResultLogDir(final String sessionId) {
    return Mono.just(props.getBaseDir() + "/" + props.getResultLogSubDir());
  }

  @Override
  public Mono<String> getDescription(final String sessionId) {
    return column(sessionId, "description");
  }

  @Override
  public Mono<Void> setDescription(final String sessionId, final String description) {
    return setIfEmpty(sessionId, "description", description);
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
  public Mono<Void> setInitiatedTime(final String sessionId, final String initiatedTime) {
    return setIfEmpty(sessionId, "initiated_time", initiatedTime);
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

  @Override
  public Mono<Void> setTags(final String sessionId, final Map<String, String> tags) {
    if (tags == null) {
      return Mono.empty();
    }
    return Mono.fromCallable(() -> mapper.writeValueAsString(tags))
        .flatMap(
            json ->
                client
                    .sql(
                        """
                        INSERT INTO session_config (session_id, tags)
                        VALUES (:s, CAST(:v AS jsonb))
                        ON CONFLICT (session_id) DO UPDATE SET tags = EXCLUDED.tags
                        WHERE session_config.tags = CAST('{}' AS jsonb)
                        """)
                    .bind("s", sessionId)
                    .bind("v", json)
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  @Override
  public Mono<Map<String, Object>> getAllConfigs(final String sessionId) {
    return client
        .sql("SELECT name FROM session_config WHERE session_id = :s")
        .bind("s", sessionId)
        .map(row -> row.get("name", String.class))
        .one()
        .flatMap(
            name ->
                Mono.zip(
                    arr -> {
                      final Map<String, Object> configs = new LinkedHashMap<>();
                      configs.put("sessionId", sessionId);
                      configs.put("projectPath", arr[0]);
                      configs.put("executionTimeout", arr[1]);
                      configs.put("fileLogDir", arr[2]);
                      configs.put("resultLogDir", arr[3]);
                      configs.put("initiator", arr[4]);
                      configs.put("initiatedTime", arr[5]);
                      configs.put("tags", arr[6]);
                      configs.put("description", arr[7]);
                      configs.put("name", name);
                      configs.put("workflows", arr[8]);
                      return configs;
                    },
                    getProjectPath(sessionId),
                    getExecutionTimeout(sessionId),
                    getFileLogDir(sessionId),
                    getResultLogDir(sessionId),
                    getInitiator(sessionId),
                    getInitiatedTime(sessionId),
                    getTags(sessionId),
                    getDescription(sessionId),
                    workflowDefinitionStore.findAll(sessionId)));
  }

  @Override
  public Flux<String> getSessionIds() {
    return client
        .sql("SELECT session_id FROM session_config")
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
