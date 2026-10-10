// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import jakarta.annotation.Nullable;

/**
 * Marker interface for execution control commands.
 *
 * <p>All execution control (pause, resume, stop, skip, step, restart) flows through the Control Bus
 * as typed command signals. Each command type implements this marker interface and can be used with
 * the unified control layer.
 *
 * <p>This unified approach ensures:
 *
 * <ul>
 *   <li>Single channel for all control operations
 *   <li>Full audit trail and observability
 *   <li>Consistent ordering via Control Bus message queue
 *   <li>No race conditions from multiple access paths
 * </ul>
 */
public sealed interface ExecutionControlCommand
    permits ExecutionControlCommand.PauseWorkflowCommand,
        ExecutionControlCommand.ResumeWorkflowCommand,
        ExecutionControlCommand.PauseNodeCommand,
        ExecutionControlCommand.ResumeNodeCommand,
        ExecutionControlCommand.StopNodeCommand,
        ExecutionControlCommand.SkipNodeCommand,
        ExecutionControlCommand.EnableStepModeCommand,
        ExecutionControlCommand.DisableStepModeCommand,
        ExecutionControlCommand.StepNodeCommand,
        ExecutionControlCommand.StopWorkflowCommand,
        ExecutionControlCommand.RestartCommand,
        ExecutionControlCommand.RestartFromNodeCommand,
        ExecutionControlCommand.SetBreakpointCommand {

  /**
   * Returns the execution identifier this command targets.
   *
   * @return the execution identifier this command targets
   */
  String executionId();

  /**
   * Pause all nodes in an execution via global backpressure.
   *
   * <p>Execution continues to process inflight work but no new elements are pulled from input
   * streams.
   *
   * @param executionId the execution identifier.
   */
  record PauseWorkflowCommand(String executionId) implements ExecutionControlCommand {}

  /**
   * Resume a paused execution.
   *
   * <p>Nodes resume normal backpressure-driven element processing.
   *
   * @param executionId the execution identifier.
   */
  record ResumeWorkflowCommand(String executionId) implements ExecutionControlCommand {}

  /**
   * Pause a single node via backpressure.
   *
   * <p>The target node stops pulling elements but inflight work continues to completion.
   */
  record PauseNodeCommand(String executionId, String nodeId) implements ExecutionControlCommand {}

  /**
   * Resume a paused node.
   *
   * <p>The node resumes pulling elements and normal processing.
   */
  record ResumeNodeCommand(String executionId, String nodeId) implements ExecutionControlCommand {}

  /**
   * Stop a single node with optional immediate vs. safe semantics.
   *
   * @param executionId the execution identifier
   * @param nodeId the target node
   * @param immediate true for hard stop (cancel upstream), false for drain & stop
   * @param reason human-readable explanation for logging
   */
  record StopNodeCommand(
      String executionId, String nodeId, boolean immediate, @Nullable String reason)
      implements ExecutionControlCommand {}

  /**
   * Mark a node as skipped or not.
   *
   * <p>On a running execution only processor nodes can be skipped. From the next element on, the
   * node passes elements through without calling the plugin; an element the plugin already has
   * finishes normally. Any other node type is rejected with an {@link IllegalArgumentException}.
   */
  record SkipNodeCommand(String executionId, String nodeId, boolean skip)
      implements ExecutionControlCommand {}

  /**
   * Enable step-through debug mode on a node.
   *
   * <p>Each element must be explicitly stepped via {@link StepNodeCommand}. The node automatically
   * pauses until step signals are sent.
   */
  record EnableStepModeCommand(String executionId, String nodeId)
      implements ExecutionControlCommand {}

  /**
   * Disable step-through debug mode on a node.
   *
   * <p>The node returns to normal pause/resume behaviour.
   */
  record DisableStepModeCommand(String executionId, String nodeId)
      implements ExecutionControlCommand {}

  /**
   * Step to the next element when a node is in step-through mode.
   *
   * <p>Allows exactly one element to pass before blocking again. No-op if node is not in step mode.
   */
  record StepNodeCommand(String executionId, String nodeId) implements ExecutionControlCommand {}

  /**
   * Stop all nodes in an execution and terminate the workflow.
   *
   * <p>The {@link StopMode} decides how: {@code DRAIN} (default) stops the triggers and lets
   * in-flight work finish, {@code CANCEL} cancels at once, {@code KILL} also removes the execution.
   *
   * @param executionId the execution identifier
   * @param reason human-readable explanation for logging
   * @param mode how to stop; null means {@link StopMode#DRAIN}
   */
  record StopWorkflowCommand(String executionId, @Nullable String reason, @Nullable StopMode mode)
      implements ExecutionControlCommand {

    /**
     * Normalises the mode.
     *
     * @param executionId the execution identifier
     * @param reason human-readable explanation for logging
     * @param mode how to stop; null means DRAIN
     */
    public StopWorkflowCommand {
      mode = mode == null ? StopMode.DRAIN : mode;
    }

    /**
     * Creates a drain stop.
     *
     * @param executionId the execution identifier
     * @param reason human-readable explanation for logging
     */
    public StopWorkflowCommand(final String executionId, @Nullable final String reason) {
      this(executionId, reason, StopMode.DRAIN);
    }
  }

  /**
   * Stop the current execution and restart from the beginning with the original payload.
   *
   * @param executionId the execution to restart
   * @param newExecutionId the pre-generated identifier for the restarted execution
   */
  record RestartCommand(String executionId, String newExecutionId)
      implements ExecutionControlCommand {}

  /**
   * Stop the current execution and restart from a specific node.
   *
   * <p>The restart uses the last checkpoint messages from the target node's direct parents,
   * allowing partial replay.
   *
   * @param executionId the execution to restart
   * @param fromNodeId the node from which to resume execution
   * @param newExecutionId the pre-generated identifier for the restarted execution
   */
  record RestartFromNodeCommand(String executionId, String fromNodeId, String newExecutionId)
      implements ExecutionControlCommand {}

  /**
   * Set or clear a breakpoint on a node: the node holds before the first element that matches, as
   * if it was paused, until it is resumed. The breakpoint stays set and hits again.
   *
   * <p>The condition is declarative, so it can come from a remote caller: {@code field} is {@code
   * payload.some.path} (nested map access) or {@code metadata.key}, and the element matches when
   * the value's text equals {@code equalsValue}. A null {@code field} clears the breakpoint.
   *
   * @param executionId the execution identifier
   * @param nodeId the node to hold
   * @param field what to look at, or null to clear the breakpoint
   * @param equalsValue the text the value must equal
   */
  record SetBreakpointCommand(
      String executionId, String nodeId, @Nullable String field, @Nullable String equalsValue)
      implements ExecutionControlCommand {

    /**
     * Checks that a condition is complete.
     *
     * @param executionId the execution identifier
     * @param nodeId the node to hold
     * @param field what to look at, or null to clear the breakpoint
     * @param equalsValue the text the value must equal
     */
    public SetBreakpointCommand {
      if (field != null
          && !"payload".equals(field)
          && !field.startsWith("payload.")
          && !field.startsWith("metadata.")) {
        throw new IllegalArgumentException(
            "Breakpoint field must be 'payload', 'payload.<path>' or 'metadata.<key>': " + field);
      }
      if (field != null && equalsValue == null) {
        throw new IllegalArgumentException("A breakpoint needs a value to compare with");
      }
    }
  }
}
