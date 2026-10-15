// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Map;
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
  default Flux<DefinitionRevision> revisions(String sessionId, String workflowId) {
    return Flux.empty();
  }

  /**
   * Find one saved version of a workflow.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param revisionId the version, as listed by {@link #revisions}
   * @return Mono containing the definition, or empty if there is no such version
   */
  default Mono<WorkflowDefinition> findRevision(
      String sessionId, String workflowId, String revisionId) {
    return Mono.empty();
  }

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
  default Mono<Void> saveNote(String sessionId, String workflowId, String revisionId, String note) {
    return Mono.error(new UnsupportedOperationException("This store keeps no version notes"));
  }
}
