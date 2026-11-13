// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Persistent store for workflow definitions, keyed by sessionId and workflowId. */
public interface WorkflowDefinitionStore {

  /**
   * Save or replace a workflow definition for a session.
   *
   * @param sessionId the session identifier
   * @param definition the workflow definition (workflowId is taken from definition.workflowId())
   * @return Mono that completes when saved
   */
  Mono<Void> save(String sessionId, WorkflowDefinition definition);

  /**
   * Replace the current definition of a workflow, or remove it, only while it still equals {@code
   * expected}: a compare-and-set, so a rollback does not overwrite what another apply (on this or
   * another server) saved in between. The default checks and then writes, which holds for a store
   * that one process owns; a store shared by several servers overrides it to be atomic.
   *
   * @param sessionId the session identifier
   * @param expected the definition the caller saved and expects to be current
   * @param replacement the definition to put back, or empty to remove the workflow
   * @return true when the replacement was made, false when the current definition had changed
   */
  default Mono<Boolean> replaceIfCurrent(
      final String sessionId,
      final WorkflowDefinition expected,
      final Optional<WorkflowDefinition> replacement) {
    return find(sessionId, expected.workflowId())
        .filter(expected::equals)
        .flatMap(
            _ ->
                replacement
                    .map(previous -> save(sessionId, previous))
                    .orElseGet(() -> remove(sessionId, expected.workflowId()))
                    .thenReturn(true))
        .defaultIfEmpty(false);
  }

  /**
   * Find a workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the definition, or empty if not found
   */
  Mono<WorkflowDefinition> find(String sessionId, String workflowId);

  /**
   * Find all workflow definitions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of workflowId to definition (empty map if none)
   */
  Mono<Map<String, WorkflowDefinition>> findAll(String sessionId);

  /**
   * Remove a single workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono that completes when removed
   */
  Mono<Void> remove(String sessionId, String workflowId);

  /**
   * Remove all workflow definitions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono that completes when all removed
   */
  Mono<Void> removeAll(String sessionId);

  /**
   * The saved versions of a workflow, newest first. A store that keeps no history returns none.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return the versions, newest first
   */
  Flux<DefinitionRevision> revisions(String sessionId, String workflowId);

  /**
   * Find one saved version of a workflow.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param revisionId the version, as listed by {@link #revisions}
   * @return Mono containing the definition, or empty if there is no such version
   */
  Mono<WorkflowDefinition> findRevision(String sessionId, String workflowId, String revisionId);

  /**
   * Write or clear the note of a saved version.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param revisionId the version, as listed by {@link #revisions}
   * @param note the note; null or blank clears it
   * @return Mono that completes when saved, or fails with NotFoundException for an unknown version
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  Mono<Void> saveNote(String sessionId, String workflowId, String revisionId, String note);
}
