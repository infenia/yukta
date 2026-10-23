// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.workflow.store.DefinitionRevision;
import com.infenia.yukta.service.workflow.store.DefinitionRevisions;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps workflow definitions and the newest {@value #MAX_REVISIONS} saved versions of each in
 * PostgreSQL. A save, the prune of old versions and a removal each run in one transaction; saving
 * the definition that is already current adds no version.
 */
@RequiredArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
public class R2dbcWorkflowDefinitionStore implements WorkflowDefinitionStore {

  /** Most versions kept per workflow. */
  public static final int MAX_REVISIONS = 20;

  /** Runs the statements. */
  private final DatabaseClient client;

  /** Reads and writes the definitions as JSON. */
  private final ObjectMapper mapper;

  @Override
  public Mono<Void> save(final String sessionId, final WorkflowDefinition definition) {
    return Transactions.inTransaction(client, write(sessionId, definition));
  }

  private Mono<Void> write(final String sessionId, final WorkflowDefinition definition) {
    return Mono.fromCallable(() -> mapper.writeValueAsString(definition))
        .flatMap(
            json -> {
              final Instant now = Instant.now();
              return client
                  .sql(
                      """
                      WITH same AS (
                        SELECT 1 FROM workflow_definition
                        WHERE session_id = :s AND workflow_id = :w
                          AND definition = CAST(:d AS jsonb)
                      ), revision AS (
                        INSERT INTO workflow_definition_revision
                          (session_id, workflow_id, revision_id, definition, saved_at)
                        SELECT CAST(:s AS text), CAST(:w AS text), CAST(:r AS text),
                               CAST(:d AS jsonb), CAST(:t AS timestamptz)
                        WHERE NOT EXISTS (SELECT 1 FROM same)
                      )
                      INSERT INTO workflow_definition (session_id, workflow_id, definition)
                      VALUES (:s, :w, CAST(:d AS jsonb))
                      ON CONFLICT (session_id, workflow_id)
                      DO UPDATE SET definition = EXCLUDED.definition, updated_at = now()
                      """)
                  .bind("s", sessionId)
                  .bind("w", definition.workflowId())
                  .bind("d", json)
                  .bind("r", DefinitionRevisions.idOf(now, definition))
                  .bind("t", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                  .fetch()
                  .rowsUpdated()
                  .then(prune(sessionId, definition.workflowId()));
            });
  }

  private Mono<Void> prune(final String sessionId, final String workflowId) {
    return client
        .sql(
            """
            DELETE FROM workflow_definition_revision
            WHERE session_id = :s AND workflow_id = :w AND revision_id NOT IN (
              SELECT revision_id FROM workflow_definition_revision
              WHERE session_id = :s AND workflow_id = :w
              ORDER BY revision_id DESC LIMIT :n)
            """)
        .bind("s", sessionId)
        .bind("w", workflowId)
        .bind("n", MAX_REVISIONS)
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Mono<WorkflowDefinition> find(final String sessionId, final String workflowId) {
    return client
        .sql(
            """
            SELECT CAST(definition AS text) AS definition FROM workflow_definition
            WHERE session_id = :s AND workflow_id = :w
            """)
        .bind("s", sessionId)
        .bind("w", workflowId)
        .map(row -> row.get("definition", String.class))
        .one()
        .map(this::read);
  }

  @Override
  public Mono<Map<String, WorkflowDefinition>> findAll(final String sessionId) {
    return client
        .sql(
            "SELECT CAST(definition AS text) AS definition FROM workflow_definition"
                + " WHERE session_id = :s")
        .bind("s", sessionId)
        .map(row -> row.get("definition", String.class))
        .all()
        .map(this::read)
        .collectMap(WorkflowDefinition::workflowId)
        .map(found -> Map.copyOf(new HashMap<>(found)));
  }

  @Override
  public Mono<Void> remove(final String sessionId, final String workflowId) {
    return Transactions.inTransaction(client, delete(sessionId, workflowId));
  }

  private Mono<Void> delete(final String sessionId, final String workflowId) {
    return client
        .sql("DELETE FROM workflow_definition_revision WHERE session_id = :s AND workflow_id = :w")
        .bind("s", sessionId)
        .bind("w", workflowId)
        .fetch()
        .rowsUpdated()
        .then(
            client
                .sql("DELETE FROM workflow_definition WHERE session_id = :s AND workflow_id = :w")
                .bind("s", sessionId)
                .bind("w", workflowId)
                .fetch()
                .rowsUpdated())
        .then();
  }

  @Override
  public Mono<Void> removeAll(final String sessionId) {
    return Transactions.inTransaction(client, deleteAll(sessionId));
  }

  private Mono<Void> deleteAll(final String sessionId) {
    return client
        .sql("DELETE FROM workflow_definition_revision WHERE session_id = :s")
        .bind("s", sessionId)
        .fetch()
        .rowsUpdated()
        .then(
            client
                .sql("DELETE FROM workflow_definition WHERE session_id = :s")
                .bind("s", sessionId)
                .fetch()
                .rowsUpdated())
        .then();
  }

  @Override
  public Flux<DefinitionRevision> revisions(final String sessionId, final String workflowId) {
    return client
        .sql(
            """
            SELECT revision_id, saved_at, note FROM workflow_definition_revision
            WHERE session_id = :s AND workflow_id = :w ORDER BY revision_id DESC
            """)
        .bind("s", sessionId)
        .bind("w", workflowId)
        .map(
            row ->
                new DefinitionRevision(
                    row.get("revision_id", String.class),
                    Objects.requireNonNull(row.get("saved_at", OffsetDateTime.class)).toInstant(),
                    row.get("note", String.class)))
        .all();
  }

  @Override
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<Void> saveNote(
      final String sessionId, final String workflowId, final String revisionId, final String note) {
    return client
        .sql(
            """
            UPDATE workflow_definition_revision SET note = NULLIF(CAST(:n AS text), '')
            WHERE session_id = :s AND workflow_id = :w AND revision_id = :r
            """)
        .bind("n", note == null || note.isBlank() ? "" : note)
        .bind("s", sessionId)
        .bind("w", workflowId)
        .bind("r", revisionId)
        .fetch()
        .rowsUpdated()
        .flatMap(
            rows ->
                rows == 0
                    ? Mono.<Void>error(new NotFoundException("Version not found: " + revisionId))
                    : Mono.<Void>empty());
  }

  @Override
  public Mono<WorkflowDefinition> findRevision(
      final String sessionId, final String workflowId, final String revisionId) {
    return client
        .sql(
            """
            SELECT CAST(definition AS text) AS definition FROM workflow_definition_revision
            WHERE session_id = :s AND workflow_id = :w AND revision_id = :r
            """)
        .bind("s", sessionId)
        .bind("w", workflowId)
        .bind("r", revisionId)
        .map(row -> row.get("definition", String.class))
        .one()
        .map(this::read);
  }

  private WorkflowDefinition read(final String json) {
    return mapper.readValue(json, WorkflowDefinition.class);
  }
}
