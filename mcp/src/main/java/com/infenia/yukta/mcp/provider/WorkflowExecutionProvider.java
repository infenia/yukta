// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Handles workflow execution operations including definition retrieval, start operations, status
 * snapshots, and execution history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionProvider {

  /** Service for executing workflows and managing their lifecycle. */
  private final WorkflowService workflowService;

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Gateway for workflow control and observability operations. */
  private final ControlBusGateway controlBus;

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return sessionService.getSessionWorkflow(sessionId, workflowId);
  }

  /**
   * Start a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the start result with the new execution ID
   */
  public Mono<WorkflowStartResult> startWorkflow(final String sessionId, final String workflowId) {
    return workflowService
        .validateAndStartWorkflow(sessionId, workflowId)
        .map(WorkflowExecution::executionId)
        .map(WorkflowStartResult::new);
  }

  /**
   * Get the current progress snapshot of a workflow execution owned by the session.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @return Mono containing the current workflow progress; errors if the execution is unknown or
   *     owned by another session
   */
  public Mono<WorkflowProgress> getWorkflowStatus(
      final String sessionId, final String executionId) {
    return ExecutionOwnership.require(controlBus, sessionId, executionId);
  }

  /**
   * Get the execution history of a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the list of execution summaries; errors if the session is unknown
   */
  public Mono<List<WorkflowExecutionSummary>> getWorkflowHistory(final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .flatMap(config -> controlBus.getHistory(sessionId))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Session not found: "
                            + sessionId
                            + ". Use list_sessions to see available sessions.")));
  }
}
