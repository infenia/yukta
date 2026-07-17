// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default implementation of WorkflowControlProvider. Mirrors the session-ownership semantics of the
 * web layer: an execution that does not exist and an execution owned by another session both
 * surface the same "Execution not found" error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public class DefaultWorkflowControlProvider implements WorkflowControlProvider {

  /** Reason recorded for stop actions when the caller does not provide one. */
  private static final String DEFAULT_REASON = "Requested via MCP";

  /** Gateway for workflow control operations. */
  private final ControlBusGateway controlBus;

  @Override
  @SuppressWarnings("PMD.OnlyOneReturn")
  public Mono<ControlActionResult> controlWorkflow(
      final String sessionId,
      final WorkflowControlAction action,
      final String executionId,
      final String workflowId,
      final String fromNodeId,
      final String reason) {
    if (action == WorkflowControlAction.STOP_ALL) {
      return stopAllExecutions(sessionId, workflowId, reason);
    }
    if (executionId == null || executionId.isBlank()) {
      return Mono.error(
          new IllegalArgumentException("executionId is required for action " + action + "."));
    }
    if (action == WorkflowControlAction.RESTART_FROM_NODE
        && (fromNodeId == null || fromNodeId.isBlank())) {
      return Mono.error(
          new IllegalArgumentException("fromNodeId is required for action RESTART_FROM_NODE."));
    }
    return requireOwnership(sessionId, executionId)
        .flatMap(ignored -> executeWorkflowAction(action, executionId, fromNodeId, reason));
  }

  @Override
  @SuppressWarnings("PMD.OnlyOneReturn")
  public Mono<ControlActionResult> controlNode(
      final String sessionId,
      final String executionId,
      final String nodeId,
      final NodeControlAction action,
      final Boolean immediate,
      final String reason) {
    if (executionId == null || executionId.isBlank()) {
      return Mono.error(
          new IllegalArgumentException("executionId is required for action " + action + "."));
    }
    if (nodeId == null || nodeId.isBlank()) {
      return Mono.error(
          new IllegalArgumentException("nodeId is required for action " + action + "."));
    }
    return requireOwnership(sessionId, executionId)
        .flatMap(
            ignored ->
                executeNodeAction(action, executionId, nodeId, immediate, reason)
                    .thenReturn(
                        new ControlActionResult(
                            action.name(),
                            executionId,
                            nodeId,
                            List.of(),
                            action + " signal sent to node " + nodeId + ".")));
  }

  /**
   * Verify that the execution exists and belongs to the session. Mirrors the web layer's
   * executeControlSignal: a missing execution and a session mismatch yield the same error so
   * callers cannot probe executions of other sessions.
   */
  private Mono<WorkflowProgress> requireOwnership(
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

  @SuppressWarnings("PMD.OnlyOneReturn")
  private Mono<ControlActionResult> stopAllExecutions(
      final String sessionId, final String workflowId, final String reason) {
    if (workflowId == null || workflowId.isBlank()) {
      return Mono.error(
          new IllegalArgumentException("workflowId is required for action STOP_ALL."));
    }
    return controlBus
        .stopWorkflow(sessionId, workflowId, reasonOrDefault(reason))
        .map(
            stopped ->
                new ControlActionResult(
                    WorkflowControlAction.STOP_ALL.name(),
                    null,
                    null,
                    stopped,
                    "Stopped " + stopped.size() + " execution(s) of workflow " + workflowId + "."));
  }

  private Mono<ControlActionResult> executeWorkflowAction(
      final WorkflowControlAction action,
      final String executionId,
      final String fromNodeId,
      final String reason) {
    return switch (action) {
      case RESUME ->
          controlBus
              .resumeWorkflow(executionId)
              .thenReturn(result(action, executionId, List.of(), "Resume signal sent."));
      case STOP ->
          controlBus
              .stopExecution(executionId, reasonOrDefault(reason))
              .map(stopped -> result(action, executionId, List.of(stopped), "Execution stopped."));
      case RESTART ->
          controlBus
              .restartWorkflow(executionId)
              .map(
                  newId ->
                      result(
                          action,
                          executionId,
                          List.of(newId),
                          "Restarted as execution " + newId + "."));
      case RESTART_FROM_NODE ->
          controlBus
              .restartFromNode(executionId, fromNodeId)
              .map(
                  newId ->
                      result(
                          action,
                          executionId,
                          List.of(newId),
                          "Restarted from node " + fromNodeId + " as execution " + newId + "."));
      // STOP_ALL is dispatched before the ownership check; PAUSE is the remaining action.
      default ->
          controlBus
              .pauseWorkflow(executionId)
              .thenReturn(result(action, executionId, List.of(), "Pause signal sent."));
    };
  }

  private Mono<Void> executeNodeAction(
      final NodeControlAction action,
      final String executionId,
      final String nodeId,
      final Boolean immediate,
      final String reason) {
    return switch (action) {
      case PAUSE -> controlBus.pauseNode(executionId, nodeId);
      case RESUME -> controlBus.resumeNode(executionId, nodeId);
      case STOP ->
          controlBus.stopNode(
              executionId, nodeId, Boolean.TRUE.equals(immediate), reasonOrDefault(reason));
      case SKIP -> controlBus.skipNode(executionId, nodeId, true);
      case UNSKIP -> controlBus.skipNode(executionId, nodeId, false);
      case STEP -> controlBus.stepNode(executionId, nodeId);
      case STEP_ENABLE -> controlBus.enableStepMode(executionId, nodeId);
      case STEP_DISABLE -> controlBus.disableStepMode(executionId, nodeId);
    };
  }

  private ControlActionResult result(
      final WorkflowControlAction action,
      final String executionId,
      final List<String> resultIds,
      final String message) {
    return new ControlActionResult(action.name(), executionId, null, resultIds, message);
  }

  private String reasonOrDefault(final String reason) {
    return reason == null || reason.isBlank() ? DEFAULT_REASON : reason;
  }
}
