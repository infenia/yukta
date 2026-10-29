// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Unified gateway for all control bus operations.
 *
 * <p>Single entry point for managing the control bus. Provides three distinct operation groups:
 *
 * <ul>
 *   <li><strong>Plugin & Message Management</strong> (internal use): Low-level plugin lifecycle and
 *       command routing
 *   <li><strong>Execution Control</strong> (REST layer): High-level execution control commands
 *       (pause, resume, stop, skip, step, restart)
 *   <li><strong>Observability</strong> (REST layer): Real-time progress tracking, logs, and
 *       execution history
 * </ul>
 *
 * <p>All control commands flow through a single channel ensuring consistent ordering, audit trails,
 * and no race conditions from multiple access paths.
 */
@SuppressWarnings("PMD.TooManyMethods")
public interface ControlBusGateway {

  // --- Plugin & Message Management (Internal) ---

  /**
   * Emit a control message to the bus.
   *
   * @param <T> the type of the control payload
   * @param signal the control message to emit
   * @return a Mono that completes when the signal has been emitted
   */
  <T> Mono<Void> emit(Message<T> signal);

  /**
   * Register a plugin to receive control signals for a specific workflow node.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  void registerPlugin(String workflowId, String nodeId, Plugin plugin);

  /**
   * Unregister a plugin from the control bus.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   */
  void unregisterPlugin(String workflowId, String nodeId);

  /**
   * Send a command to a specific node in a workflow and wait for response.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the target node identifier
   * @param command the command message
   * @return a Mono of the response message
   */
  Mono<Message<?>> sendCommand(String workflowId, String nodeId, Message<?> command);

  // --- Configuration & Preparation ---

  /**
   * Compile and cache a workflow after it has already been persisted.
   *
   * <p>Assumes the workflow is already in {@link
   * com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore}. Invalidates stale cache
   * entries, compiles the workflow, and warms the cache.
   *
   * @param sessionId the session that owns this workflow
   * @param workflowDefinition the workflow definition to compile and cache
   * @return a Mono that completes when the workflow is compiled and cached
   */
  Mono<Void> compileAndCacheWorkflow(String sessionId, WorkflowDefinition workflowDefinition);

  // --- Execution Control (REST Layer) ---

  /**
   * Execute a control command through the control bus.
   *
   * @param <T> the command type
   * @param command the control command message to execute
   * @return a Mono that completes when the command has been routed through the bus
   */
  <T extends ExecutionControlCommand> Mono<Void> executeCommand(Message<T> command);

  /**
   * Pause all nodes in an execution via global backpressure.
   *
   * <p>Execution continues to process inflight work but no new elements are pulled from input
   * streams.
   *
   * @param executionId the execution to pause
   * @return a Mono that completes when the pause command is emitted
   */
  Mono<Void> pauseWorkflow(String executionId);

  /**
   * Resume a paused execution.
   *
   * <p>Nodes resume normal backpressure-driven element processing.
   *
   * @param executionId the execution to resume
   * @return a Mono that completes when the resume command is emitted
   */
  Mono<Void> resumeWorkflow(String executionId);

  /**
   * Pause a single node via backpressure.
   *
   * <p>The target node stops pulling elements but inflight work continues to completion.
   *
   * @param executionId the execution to target
   * @param nodeId the node to pause
   * @return a Mono that completes when the pause command is emitted
   */
  Mono<Void> pauseNode(String executionId, String nodeId);

  /**
   * Resume a paused node.
   *
   * <p>The node resumes pulling elements and normal processing.
   *
   * @param executionId the execution to target
   * @param nodeId the node to resume
   * @return a Mono that completes when the resume command is emitted
   */
  Mono<Void> resumeNode(String executionId, String nodeId);

  /**
   * Stop a single node.
   *
   * @param executionId the execution to target
   * @param nodeId the node to stop
   * @param immediate true for hard stop (cancel upstream), false for drain & stop
   * @param reason human-readable explanation for logging
   * @return a Mono that completes when the stop command is emitted
   */
  Mono<Void> stopNode(String executionId, String nodeId, boolean immediate, String reason);

  /**
   * Mark a node as skipped or not.
   *
   * <p>When skipped, the node passes messages through without invoking the processor. When not
   * skipped, normal processing resumes.
   *
   * @param executionId the execution to target
   * @param nodeId the node to skip
   * @param skip true to skip, false to unskip
   * @return a Mono that completes when the skip command is emitted
   */
  Mono<Void> skipNode(String executionId, String nodeId, boolean skip);

  /**
   * Set a breakpoint on a node: it holds before the first element that matches, as if paused, until
   * resumed. The breakpoint stays set and hits again.
   *
   * @param executionId the execution to target
   * @param nodeId the node to hold
   * @param field {@code payload}, {@code payload.some.path} or {@code metadata.key}
   * @param equalsValue the text the value at {@code field} must equal
   * @return a Mono that completes when the breakpoint is set
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  Mono<Void> addBreakpoint(String executionId, String nodeId, String field, String equalsValue);

  /**
   * Send a named signal (an approval, a decision) to a node that waits for it. The command is
   * reviewed, ordered and audited like every other control command.
   *
   * @param executionId the execution to target
   * @param nodeId the node that waits
   * @param name the signal the node waits for
   * @param decision what was decided, such as {@code approve} or {@code reject}
   * @param data optional text that goes along with the decision
   * @return a Mono that completes when the node took the signal; errors with a conflict if the node
   *     is not waiting for it
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  Mono<Void> signalNode(
      String executionId, String nodeId, String name, String decision, String data);

  /**
   * Clear the breakpoint of a node.
   *
   * @param executionId the execution to target
   * @param nodeId the node
   * @return a Mono that completes when the breakpoint is cleared
   */
  Mono<Void> clearBreakpoint(String executionId, String nodeId);

  /**
   * Enable step-through debug mode on a node.
   *
   * <p>Each element must be explicitly stepped via {@link #stepNode}. The node automatically pauses
   * until step signals are sent.
   *
   * @param executionId the execution to target
   * @param nodeId the node to enable step mode on
   * @return a Mono that completes when the enable command is emitted
   */
  Mono<Void> enableStepMode(String executionId, String nodeId);

  /**
   * Disable step-through debug mode on a node.
   *
   * <p>The node returns to normal pause/resume behavior.
   *
   * @param executionId the execution to target
   * @param nodeId the node to disable step mode on
   * @return a Mono that completes when the disable command is emitted
   */
  Mono<Void> disableStepMode(String executionId, String nodeId);

  /**
   * Step to the next element when a node is in step-through mode.
   *
   * <p>Allows exactly one element to pass through the node before blocking again.
   *
   * @param executionId the execution to target
   * @param nodeId the node to step
   * @return a Mono that completes when the step command is emitted
   */
  Mono<Void> stepNode(String executionId, String nodeId);

  /**
   * Safely stop all active workflow executions for a session and workflow.
   *
   * <p>Emits a safe-stop signal for each active execution that drains inflight work before
   * terminating. The trigger plugin's stream is also severed, preventing any new input from
   * starting new executions.
   *
   * @param sessionId the session that owns the workflow
   * @param workflowId the workflow to stop
   * @param reason human-readable explanation for logging
   * @return a Mono containing a list of stopped execution IDs, or an error if no active executions
   *     exist
   */
  default Mono<List<String>> stopWorkflow(
      final String sessionId, final String workflowId, final String reason) {
    return stopWorkflow(sessionId, workflowId, reason, StopMode.DRAIN);
  }

  /**
   * Stops every active execution of a workflow in the given way.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param reason human-readable explanation for logging
   * @param mode how to stop
   * @return the stopped execution IDs, emitted once every stop command has been applied
   */
  Mono<List<String>> stopWorkflow(
      String sessionId, String workflowId, String reason, StopMode mode);

  /**
   * Safely stop a specific workflow execution by execution ID.
   *
   * <p>Emits a safe-stop signal that drains inflight work before terminating.
   *
   * @param executionId the execution to stop
   * @param reason human-readable explanation for logging
   * @return a Mono containing the stopped execution ID, or an error if execution not found
   */
  default Mono<String> stopExecution(final String executionId, final String reason) {
    return stopExecution(executionId, reason, StopMode.DRAIN);
  }

  /**
   * Stops a specific workflow execution by execution ID in the given way.
   *
   * @param executionId the execution to stop
   * @param reason human-readable explanation for logging
   * @param mode how to stop
   * @return a Mono containing the stopped execution ID, or an error if execution not found
   */
  Mono<String> stopExecution(String executionId, String reason, StopMode mode);

  /**
   * Start a new workflow execution for a session with an empty trigger payload.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a Mono containing the execution ID
   */
  default Mono<String> startWorkflow(final String sessionId, final String workflowId) {
    return startWorkflow(sessionId, workflowId, Map.of());
  }

  /**
   * Start a new workflow execution for a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payload the trigger payload, read by the workflow's trigger node
   * @return a Mono containing the execution ID
   */
  Mono<String> startWorkflow(String sessionId, String workflowId, Map<String, Object> payload);

  /**
   * Start a new workflow execution, safely retryable with an idempotency key.
   *
   * <p>A repeated key for the same session and workflow within the idempotency window returns the
   * execution started the first time instead of starting another one.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payload the trigger payload, read by the workflow's trigger node
   * @param idempotencyKey the client's key, or null for a plain start
   * @return a Mono containing the execution ID
   */
  Mono<String> startWorkflow(
      String sessionId, String workflowId, Map<String, Object> payload, String idempotencyKey);

  /**
   * Safely stop the current execution and restart the entire workflow from the beginning using the
   * original payload.
   *
   * @param executionId the execution to restart
   * @return a Mono containing the newly generated execution ID
   */
  Mono<String> restartWorkflow(String executionId);

  /**
   * Safely stop the current execution and restart the workflow from a specific node, replaying the
   * last known checkpoints for its parent nodes.
   *
   * @param executionId the execution to restart
   * @param fromNodeId the node from which to resume execution
   * @return a Mono containing the newly generated execution ID
   */
  Mono<String> restartFromNode(String executionId, String fromNodeId);

  /**
   * Re-run a node that failed, with the output its parents recorded, together with everything
   * behind it. Fails if the node did not fail in that execution.
   *
   * @param executionId the execution the node failed in
   * @param nodeId the failed node
   * @return a Mono containing the newly generated execution ID
   */
  Mono<String> retryNode(String executionId, String nodeId);

  /**
   * Change how many executions may run at once; raising a limit admits waiting starts at once.
   *
   * @param maxConcurrent global limit, 0 for none
   * @param maxPerWorkflow per-workflow limit, 0 for none
   * @return a Mono that completes when the limits are changed
   */
  Mono<Void> changeLimits(int maxConcurrent, int maxPerWorkflow);

  /**
   * Change the priority of an execution that is waiting for a slot. It is recorded in the execution
   * log like any control command.
   *
   * @param executionId the waiting execution
   * @param priority higher is admitted first
   * @return a Mono that completes when the priority is changed; errors if the execution is not
   *     waiting
   */
  Mono<Void> changePriority(String executionId, int priority);

  // --- Observability ---

  /**
   * Watch a specific execution for status updates with optional history.
   *
   * <p>If includeHistory is true, the returned flux emits cached historical updates before live
   * updates. Stream auto-completes when the workflow reaches a terminal state.
   *
   * @param executionId the execution identifier
   * @param includeHistory if true, emit cached history before live updates
   * @return a flux of workflow progress updates
   */
  reactor.core.publisher.Flux<com.infenia.yukta.model.execution.WorkflowProgress> watchExecution(
      String executionId, boolean includeHistory);

  /**
   * Watch workflow logs in real-time.
   *
   * <p>Emits log lines as they are generated during execution. Useful for live log streaming to
   * UIs.
   *
   * @param sessionId the session identifier
   * @param executionId the execution to watch
   * @return a Flux of log lines that completes when execution finishes
   */
  reactor.core.publisher.Flux<String> watchLogs(String sessionId, String executionId);

  /**
   * Get current execution progress snapshot.
   *
   * <p>Returns the current progress of an execution without subscribing to a stream. Useful for
   * one-off queries.
   *
   * @param executionId the execution to query
   * @return the current progress, or null if execution not found
   */
  com.infenia.yukta.model.execution.WorkflowProgress getCurrentProgress(String executionId);

  /**
   * The nodes of a session that wait for a signal (an approval or decision) now, oldest first.
   *
   * @param sessionId the session
   * @return one entry per open wait
   */
  reactor.core.publisher.Flux<com.infenia.yukta.model.control.PendingApproval> pendingApprovals(
      String sessionId);

  /**
   * Get the progress of an execution within a session.
   *
   * <p>Unlike {@link #getCurrentProgress(String)}, this reads the per-session record, which
   * outlives the short-lived execution index, so finished executions stay reachable while they are
   * in the session's history.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution to query
   * @return the progress, or null if the session has no such execution
   */
  com.infenia.yukta.model.execution.WorkflowProgress getProgress(
      String sessionId, String executionId);

  /**
   * Get execution history for a session.
   *
   * <p>Returns all completed and in-progress executions for a session. Useful for listing past
   * runs.
   *
   * @param sessionId the session to query
   * @return Mono containing list of execution summaries
   */
  Mono<List<com.infenia.yukta.model.execution.WorkflowExecutionSummary>> getHistory(
      String sessionId);

  /**
   * Get execution history for a single workflow within a session.
   *
   * @param sessionId the session to query
   * @param workflowId the workflow to query
   * @return Mono containing list of execution summaries for the given workflow
   */
  Mono<List<com.infenia.yukta.model.execution.WorkflowExecutionSummary>> getHistory(
      String sessionId, String workflowId);

  // --- State Queries ---

  /**
   * Get the last heartbeat for a node in a specific workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null if none
   */
  Message<?> getLastHeartbeat(String workflowId, String nodeId);

  /**
   * Get the last statistics message for a node in a specific workflow.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the last statistics message, or null if none
   */
  Message<?> getLastStatistics(String workflowId, String nodeId);

  /**
   * List all node IDs in a specific workflow that have emitted heartbeats.
   *
   * @param workflowId the workflow identifier
   * @return list of node IDs scoped to the workflow
   */
  List<String> getActiveNodes(String workflowId);

  /**
   * List all node IDs across all workflows that have emitted heartbeats.
   *
   * @return list of all active node IDs
   */
  List<String> getActiveNodes();
}
