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
import reactor.core.scheduler.Schedulers;

/**
 * Default implementation of WorkflowExecutionProvider. Handles workflow execution operations
 * including definition retrieval, start operations, status snapshots, and execution history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultWorkflowExecutionProvider implements WorkflowExecutionProvider {

  /** Service for executing workflows and managing their lifecycle. */
  private final WorkflowService workflowService;

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Gateway for workflow control and observability operations. */
  private final ControlBusGateway controlBus;

  @Override
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return sessionService.getSessionWorkflow(sessionId, workflowId);
  }

  @Override
  public Mono<WorkflowStartResult> startWorkflow(final String sessionId, final String workflowId) {
    return workflowService
        .validateAndStartWorkflow(sessionId, workflowId)
        .map(WorkflowExecution::executionId)
        .map(WorkflowStartResult::new);
  }

  @Override
  public Mono<WorkflowProgress> getWorkflowStatus(
      final String sessionId, final String executionId) {
    return Mono.fromCallable(() -> controlBus.getCurrentProgress(executionId))
        .subscribeOn(Schedulers.boundedElastic())
        .filter(progress -> progress.sessionId().equals(sessionId))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Execution not found: "
                            + executionId
                            + ". Use get_workflow_history to list executions.")));
  }

  @Override
  public Mono<List<WorkflowExecutionSummary>> getWorkflowHistory(final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .flatMap(
            ignored ->
                Mono.fromCallable(() -> controlBus.getHistory(sessionId))
                    .subscribeOn(Schedulers.boundedElastic()))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Session not found: "
                            + sessionId
                            + ". Use list_sessions to see available sessions.")));
  }
}
