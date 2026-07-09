// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import reactor.core.publisher.Mono;

/**
 * Provider for workflow execution operations. Handles workflow definition retrieval, trigger
 * operations, and status monitoring.
 */
public interface WorkflowExecutionProvider {

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  Mono<WorkflowDefinition> getWorkflowDetails(String sessionId, String workflowId);

  /**
   * Trigger a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payloadJson optional JSON string for trigger payload
   * @return Mono containing the execution ID
   */
  Mono<String> triggerWorkflow(String sessionId, String workflowId, String payloadJson);

  /**
   * Get status of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return Mono containing workflow execution summary
   */
  Mono<WorkflowExecutionSummary> getWorkflowStatus(String sessionId, String executionId);
}
