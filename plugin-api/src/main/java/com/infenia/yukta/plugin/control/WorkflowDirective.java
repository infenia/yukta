// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/**
 * A typed action to be applied to an active workflow execution.
 *
 * <p>{@code WorkflowDirective} is the output side of the control-bus plugin pipeline. A {@link
 * ControlSignalProcessor} converts an inbound {@link
 * com.infenia.yukta.plugin.message.control.ControlCommand} into one of the concrete directive
 * types. The {@code DirectiveDispatcher} then pattern-matches on the sealed hierarchy to apply the
 * correct runtime behaviour.
 *
 * <p>Sealed permits list is exhaustive: the dispatcher can switch without a default branch.
 */
public sealed interface WorkflowDirective
    permits WorkflowDirective.Stop, WorkflowDirective.Restart, WorkflowDirective.RestartFromNode {

  /**
   * Terminates the active execution immediately.
   *
   * @param reason human-readable explanation, included in logs
   */
  record Stop(String reason) implements WorkflowDirective {}

  /** Stops the current execution and re-runs it from the beginning with the original payload. */
  record Restart() implements WorkflowDirective {}

  /**
   * Stops the current execution and replays from a specific node using the last checkpoint messages
   * produced by that node's direct parents.
   *
   * @param nodeId the node from which execution should resume
   */
  record RestartFromNode(String nodeId) implements WorkflowDirective {}
}
