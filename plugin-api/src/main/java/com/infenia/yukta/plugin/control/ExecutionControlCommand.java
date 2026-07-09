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
public interface ExecutionControlCommand {

  /**
   * Returns the execution identifier this command targets.
   *
   * @return the execution identifier this command targets
   */
  String executionId();

  /**
   * Returns the command type identifier.
   *
   * @return the command type identifier
   */
  String commandType();

  /**
   * Pause all nodes in an execution via global backpressure.
   *
   * <p>Execution continues to process inflight work but no new elements are pulled from input
   * streams.
   *
   * @param executionId the execution identifier.
   */
  record PauseWorkflowCommand(String executionId) implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.pause-workflow";
    }
  }

  /**
   * Resume a paused execution.
   *
   * <p>Nodes resume normal backpressure-driven element processing.
   *
   * @param executionId the execution identifier.
   */
  record ResumeWorkflowCommand(String executionId) implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.resume-workflow";
    }
  }

  /**
   * Pause a single node via backpressure.
   *
   * <p>The target node stops pulling elements but inflight work continues to completion.
   */
  record PauseNodeCommand(String executionId, String nodeId) implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.pause-node";
    }
  }

  /**
   * Resume a paused node.
   *
   * <p>The node resumes pulling elements and normal processing.
   */
  record ResumeNodeCommand(String executionId, String nodeId) implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.resume-node";
    }
  }

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
      implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.stop-node";
    }
  }

  /**
   * Mark a node as skipped or not.
   *
   * <p>When skipped, the node passes messages through without invoking the processor. When not
   * skipped, normal processing resumes.
   */
  record SkipNodeCommand(String executionId, String nodeId, boolean skip)
      implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.skip-node";
    }
  }

  /**
   * Enable step-through debug mode on a node.
   *
   * <p>Each element must be explicitly stepped via {@link StepNodeCommand}. The node automatically
   * pauses until step signals are sent.
   */
  record EnableStepModeCommand(String executionId, String nodeId)
      implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.enable-step-mode";
    }
  }

  /**
   * Disable step-through debug mode on a node.
   *
   * <p>The node returns to normal pause/resume behaviour.
   */
  record DisableStepModeCommand(String executionId, String nodeId)
      implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.disable-step-mode";
    }
  }

  /**
   * Step to the next element when a node is in step-through mode.
   *
   * <p>Allows exactly one element to pass before blocking again. No-op if node is not in step mode.
   */
  record StepNodeCommand(String executionId, String nodeId) implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.step-node";
    }
  }

  /**
   * Stop all nodes in an execution and terminate the workflow.
   *
   * <p>Uses safe-stop semantics: inflight work drains before the execution terminates. The trigger
   * plugin's stream is also severed, preventing any new input from being accepted.
   *
   * @param executionId the execution identifier
   * @param reason human-readable explanation for logging
   */
  record StopWorkflowCommand(String executionId, @Nullable String reason)
      implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.stop-workflow";
    }
  }

  /**
   * Stop the current execution and restart from the beginning with the original payload.
   *
   * <p>A new {@code executionId} is generated for the restarted execution.
   */
  record RestartCommand(String executionId) implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.restart";
    }
  }

  /**
   * Stop the current execution and restart from a specific node.
   *
   * <p>The restart uses the last checkpoint messages from the target node's direct parents,
   * allowing partial replay. A new {@code executionId} is generated.
   *
   * @param executionId the execution to restart
   * @param fromNodeId the node from which to resume execution
   */
  record RestartFromNodeCommand(String executionId, String fromNodeId)
      implements ExecutionControlCommand {
    @Override
    public String commandType() {
      return "execution.restart-from-node";
    }
  }
}
