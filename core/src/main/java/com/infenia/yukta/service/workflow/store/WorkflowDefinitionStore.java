// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Map;
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
}
