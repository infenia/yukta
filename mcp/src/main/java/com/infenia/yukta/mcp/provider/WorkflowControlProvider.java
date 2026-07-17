// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import reactor.core.publisher.Mono;

/**
 * Provider for workflow and node control operations. Every execution-scoped action verifies that
 * the execution belongs to the given session before sending the control signal.
 */
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public interface WorkflowControlProvider {

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
  Mono<ControlActionResult> controlWorkflow(
      String sessionId,
      WorkflowControlAction action,
      String executionId,
      String workflowId,
      String fromNodeId,
      String reason);

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
  Mono<ControlActionResult> controlNode(
      String sessionId,
      String executionId,
      String nodeId,
      NodeControlAction action,
      Boolean immediate,
      String reason);
}
