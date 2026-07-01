/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.core.Plugin;
import java.util.List;
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
  Mono<List<String>> stopWorkflow(String sessionId, String workflowId, String reason);

  /**
   * Safely stop a specific workflow execution by execution ID.
   *
   * <p>Emits a safe-stop signal that drains inflight work before terminating.
   *
   * @param executionId the execution to stop
   * @param reason human-readable explanation for logging
   * @return a Mono containing the stopped execution ID, or an error if execution not found
   */
  Mono<String> stopExecution(String executionId, String reason);

  /**
   * Start a new workflow execution for a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a Mono containing the execution ID
   */
  Mono<String> startWorkflow(String sessionId, String workflowId);

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

  // --- Observability ---

  /**
   * Watch workflow execution progress in real-time.
   *
   * <p>Emits progress updates as the execution progresses (status changes, tasks complete, etc.).
   * Useful for UI streaming, progress tracking, and monitoring.
   *
   * @param executionId the execution to watch
   * @return a Flux of progress updates that completes when execution finishes
   */
  reactor.core.publisher.Flux<com.infenia.yukta.model.execution.WorkflowProgress> watchExecution(
      String executionId);

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
   * @param executionId the execution to watch
   * @return a Flux of log lines that completes when execution finishes
   */
  reactor.core.publisher.Flux<String> watchLogs(String executionId);

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
   * Get execution history for a session.
   *
   * <p>Returns all completed and in-progress executions for a session. Useful for listing past
   * runs.
   *
   * @param sessionId the session to query
   * @return list of execution summaries
   */
  List<com.infenia.yukta.model.execution.WorkflowExecutionSummary> getHistory(String sessionId);

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
