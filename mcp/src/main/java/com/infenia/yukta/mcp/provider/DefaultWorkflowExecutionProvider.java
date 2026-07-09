// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Default implementation of WorkflowExecutionProvider. Handles workflow execution operations
 * including definition retrieval, trigger operations, and status monitoring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultWorkflowExecutionProvider implements WorkflowExecutionProvider {

  /** Service for executing workflows and managing their lifecycle. */
  private final WorkflowService workflowService;

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Service for tracking task progress and execution details. */
  private final TaskTrackerService trackerService;

  @Override
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return sessionService.getSessionWorkflow(sessionId, workflowId);
  }

  @Override
  public Mono<String> triggerWorkflow(
      final String sessionId, final String workflowId, final String payloadJson) {
    return workflowService
        .validateAndStartWorkflow(sessionId, workflowId)
        .map(WorkflowExecution::executionId);
  }

  @Override
  public Mono<WorkflowExecutionSummary> getWorkflowStatus(
      final String sessionId, final String executionId) {
    return Mono.fromCallable(() -> trackerService.getHistory(sessionId))
        .flatMapIterable(list -> list)
        .filter(s -> s.executionId().equals(executionId))
        .next();
  }
}
