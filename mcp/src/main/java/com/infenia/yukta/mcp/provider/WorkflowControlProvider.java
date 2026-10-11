// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Mirrors the session-ownership semantics of the web layer: an execution that does not exist and an
 * execution owned by another session both surface the same "Execution not found" error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.UseObjectForClearerAPI", "PMD.OnlyOneReturn"})
public class WorkflowControlProvider {

  /** Reason recorded for stop actions when the caller does not provide one. */
  private static final String DEFAULT_REASON = "Requested via MCP";

  /** Gateway for workflow control operations. */
  private final ControlBusGateway controlBus;

  /**
   * Execute a workflow-level control action.
   *
   * @param sessionId the session that owns the execution
   * @param action the control action to execute
   * @param executionId the target execution (required for all actions except {@code STOP_ALL})
   * @param workflowId the target workflow (required for {@code STOP_ALL})
   * @param fromNodeId the node to restart from (required for {@code RESTART_FROM_NODE})
   * @param reason optional reason recorded with stop actions
   * @return Mono containing the action result
   */
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
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .flatMap(ignored -> executeWorkflowAction(action, executionId, fromNodeId, reason));
  }

  /**
   * Execute a node-level control action.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the target execution
   * @param nodeId the target node
   * @param action the control action to execute
   * @param immediate whether a {@code STOP} action interrupts the node immediately
   * @param reason optional reason recorded with stop actions
   * @return Mono containing the action result
   */
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
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .flatMap(
            ignored ->
                action == NodeControlAction.RETRY
                    ? controlBus
                        .retryNode(executionId, nodeId)
                        .map(
                            newId ->
                                new ControlActionResult(
                                    action.name(),
                                    executionId,
                                    nodeId,
                                    List.of(newId),
                                    "Retried from node " + nodeId + " as execution " + newId + "."))
                    : executeNodeAction(action, executionId, nodeId, immediate, reason)
                        .thenReturn(
                            new ControlActionResult(
                                action.name(),
                                executionId,
                                nodeId,
                                List.of(),
                                action + " signal sent to node " + nodeId + ".")));
  }

  /**
   * Pause a node when a payload field has a given value.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the target execution
   * @param nodeId the target node
   * @param field the field to compare, for example {@code payload.status}
   * @param equalsValue the value that triggers the breakpoint
   * @return Mono containing the action result
   */
  public Mono<ControlActionResult> addBreakpoint(
      final String sessionId,
      final String executionId,
      final String nodeId,
      final String field,
      final String equalsValue) {
    if (executionId == null
        || executionId.isBlank()
        || nodeId == null
        || nodeId.isBlank()
        || field == null
        || field.isBlank()
        || equalsValue == null) {
      return Mono.error(
          new IllegalArgumentException(
              "executionId, nodeId, field and equalsValue are required to set a breakpoint."));
    }
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .flatMap(
            ignored ->
                controlBus
                    .addBreakpoint(executionId, nodeId, field, equalsValue)
                    .thenReturn(
                        new ControlActionResult(
                            "BREAKPOINT_SET",
                            executionId,
                            nodeId,
                            List.of(),
                            "Breakpoint set on node " + nodeId + ".")));
  }

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
      case STEP -> controlBus.stepNode(executionId, nodeId);
      case STEP_ENABLE -> controlBus.enableStepMode(executionId, nodeId);
      case STEP_DISABLE -> controlBus.disableStepMode(executionId, nodeId);
      default -> executeOtherNodeAction(action, executionId, nodeId);
    };
  }

  private Mono<Void> executeOtherNodeAction(
      final NodeControlAction action, final String executionId, final String nodeId) {
    return switch (action) {
      case SKIP -> controlBus.skipNode(executionId, nodeId, true);
      case UNSKIP -> controlBus.skipNode(executionId, nodeId, false);
      case BREAKPOINT_CLEAR -> controlBus.clearBreakpoint(executionId, nodeId);
      // RETRY is answered before this point because it creates a new execution.
      default -> Mono.error(new IllegalStateException(action + " is not a signal"));
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
