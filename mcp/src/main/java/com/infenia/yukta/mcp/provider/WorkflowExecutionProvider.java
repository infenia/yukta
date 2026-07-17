// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Provider for workflow execution operations. Handles workflow definition retrieval, start
 * operations, status snapshots, and execution history.
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
   * Start a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the start result with the new execution ID
   */
  Mono<WorkflowStartResult> startWorkflow(String sessionId, String workflowId);

  /**
   * Get the current progress snapshot of a workflow execution owned by the session.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @return Mono containing the current workflow progress; errors if the execution is unknown or
   *     owned by another session
   */
  Mono<WorkflowProgress> getWorkflowStatus(String sessionId, String executionId);

  /**
   * Get the execution history of a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the list of execution summaries; errors if the session is unknown
   */
  Mono<List<WorkflowExecutionSummary>> getWorkflowHistory(String sessionId);
}
