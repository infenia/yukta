// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.validation.NodeId;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Service interface for tracking the progress of workflows and tasks. */
@SuppressWarnings("PMD.TooManyMethods")
public interface TaskTrackerService {

  /**
   * Start tracking a new workflow execution for a session.
   *
   * @param executionId the unique execution identifier
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param nodeIds the list of node IDs in the workflow DAG
   * @return a Mono that completes when the workflow tracking is started
   */
  Mono<Void> startWorkflow(
      @NotBlank String executionId,
      @SessionId String sessionId,
      @WorkflowId String workflowId,
      @NotEmpty List<String> nodeIds);

  /**
   * Update the status of a specific node.
   *
   * @param executionId the execution identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @return a Mono that completes when the task status is updated
   */
  Mono<Void> updateTaskStatus(
      @NotBlank String executionId,
      @NodeId String nodeId,
      @NotBlank @Size(max = 256) String module,
      @NotNull TaskStatus status);

  /**
   * Update the status and metadata of a specific node.
   *
   * @param executionId the execution identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   * @return a Mono that completes when the task status is updated
   */
  Mono<Void> updateTaskStatus(
      @NotBlank String executionId,
      @NodeId String nodeId,
      @NotBlank @Size(max = 256) String module,
      @NotNull TaskStatus status,
      @NotNull Map<String, Object> metadata);

  /**
   * Finish the workflow execution.
   *
   * @param executionId the execution identifier
   * @param status the final status
   * @return a Mono that completes when the workflow is finished
   */
  Mono<Void> finishWorkflow(@NotBlank String executionId, @NotNull TaskStatus status);

  /**
   * Append a log line to the live output.
   *
   * @param executionId the execution identifier
   * @param line the log line
   * @return a Mono that completes when the log line is appended
   */
  Mono<Void> appendLog(@NotBlank String executionId, @NotBlank @Size(max = 16_384) String line);

  /**
   * Get the current progress of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return the workflow progress
   */
  WorkflowProgress getProgress(@SessionId String sessionId, @NotBlank String executionId);

  /**
   * Get workflow progress by execution ID only (independent of session).
   *
   * @param executionId the execution identifier
   * @return the workflow progress, or null if not found
   */
  WorkflowProgress getProgressByExecutionId(@NotBlank String executionId);

  /**
   * Get the latest execution ID for a workflow in a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return the execution identifier or null if none
   */
  String getLatestExecutionId(@SessionId String sessionId, @WorkflowId String workflowId);

  /**
   * Get the log stream for an execution.
   *
   * @param executionId the execution identifier
   * @return the log flux
   */
  Flux<String> getLogStream(@NotBlank String executionId);

  /**
   * Get the status stream for an execution.
   *
   * @param executionId the execution identifier
   * @return the status flux
   */
  Flux<WorkflowProgress> getStatusStream(@NotBlank String executionId);

  /**
   * Get a flux of status updates for an execution with optional history.
   *
   * @param executionId the execution identifier
   * @param includeHistory if true, emits cached history before live updates
   * @return the status flux, or empty if execution not found
   */
  Flux<WorkflowProgress> getStatusStream(@NotBlank String executionId, boolean includeHistory);

  /**
   * Get history of executions for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing list of workflow execution summaries
   */
  Mono<List<WorkflowExecutionSummary>> getHistory(@SessionId String sessionId);

  /**
   * Get history of executions for a single workflow within a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing list of workflow execution summaries for the given workflow
   */
  Mono<List<WorkflowExecutionSummary>> getHistory(
      @SessionId String sessionId, @WorkflowId String workflowId);

  /**
   * List all active sessions being tracked.
   *
   * @return list of session IDs
   */
  List<String> getActiveSessions();

  /**
   * Remove tracking data for a session.
   *
   * @param sessionId the session identifier
   */
  void removeSession(@SessionId String sessionId);

  /**
   * Records who or what started a run, for its history entry.
   *
   * @param executionId the execution
   * @param initiator the name, or null when it is not known
   */
  default void setInitiator(final String executionId, final String initiator) {
    // a tracker that keeps no history has nothing to record
  }

  /**
   * Emit a task status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   */
  void emitTaskStatusEvent(
      @NotBlank String executionId,
      @NotBlank String nodeId,
      @NotBlank @Size(max = 256) String module,
      @NotNull TaskStatus status,
      @NotNull Map<String, Object> metadata);

  /**
   * Emit a workflow status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param status the final status
   */
  void emitWorkflowStatusEvent(@NotBlank String executionId, @NotNull TaskStatus status);

  /**
   * Counts a retry of a node's processing of a message.
   *
   * @param module the node's plugin type
   */
  void recordRetry(String module);

  /**
   * Records a message that an edge buffer dropped because the node could not keep up.
   *
   * @param module the node type
   * @param nodeId the node whose buffer dropped it
   */
  void recordEdgeDropped(String module, String nodeId);

  /**
   * Record an operator control state (pause, step, skip, ...) for an execution.
   *
   * <p>Control state is reported next to, never instead of, the lifecycle status.
   *
   * @param executionId the execution identifier
   * @param state the new control state
   */
  void emitControlStateEvent(@NotBlank String executionId, @NotNull ControlState state);

  /**
   * Records the breakpoint set on (or cleared from) a node and notifies status subscribers.
   *
   * @param executionId the execution identifier
   * @param nodeId the node
   * @param breakpoint the breakpoint, or null once cleared
   */
  void emitBreakpointEvent(
      @NotBlank String executionId,
      @NotBlank String nodeId,
      TaskProgress.@Nullable Breakpoint breakpoint);

  /**
   * Record which version (content hash) of the workflow graph an execution runs, so it is reported
   * with the execution's progress. Ignored for an unknown execution.
   *
   * @param executionId the execution identifier
   * @param version the definition version
   */
  void recordDefinitionVersion(@NotBlank String executionId, @NotBlank String version);

  /**
   * Record where an execution sits in a tree of runs, so it is reported with its progress and
   * history and can be followed with its ancestors: the run that started it as a sub-workflow, and
   * the run it restarts. Its root is the top of the tree (its parent's root, or itself). Ignored
   * for an unknown execution.
   *
   * @param executionId the execution identifier
   * @param parentExecutionId the run that started it as a sub-workflow, or null
   * @param restartOf the run it restarts, or null
   */
  void recordLineage(
      @NotBlank String executionId, @Nullable String parentExecutionId, @Nullable String restartOf);

  /**
   * Whether an execution is the given run or one of its descendants (a sub-workflow it started, at
   * any depth).
   *
   * @param executionId the execution to test
   * @param ancestorId the run at the top of the tree
   * @return true when executionId is ancestorId or lies below it
   */
  boolean isWithin(@NotBlank String executionId, @NotBlank String ancestorId);

  /**
   * Where a run sits in its tree of runs.
   *
   * @param executionId the run
   * @return the run at the top of its tree and how many sub-workflow levels it lies below it; a run
   *     without a parent, or an unknown one, is its own root at depth 0
   */
  Lineage lineage(@NotBlank String executionId);

  /**
   * A run's place in its tree.
   *
   * @param rootExecutionId the run at the top of the tree
   * @param depth how many levels below the root, 0 for the root itself
   */
  record Lineage(String rootExecutionId, int depth) {}

  /**
   * The tracked runs of a tree: the given run and every run it started as a sub-workflow, at any
   * depth, oldest first.
   *
   * @param rootExecutionId the run at the top of the tree
   * @return the runs, or an empty list when the run is not tracked
   */
  List<WorkflowExecutionSummary> getTree(@NotBlank String rootExecutionId);

  /**
   * Emit a log event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   */
  void emitLogEvent(
      @NotBlank String executionId,
      @NotBlank String nodeId,
      @NotBlank @Size(max = 16_384) String line);

  /**
   * Emit a log event that says which stream the line came from. Implementations that keep no stream
   * ignore it.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @param stderr true if the line came from the plugin's error stream
   */
  default void emitLogEvent(
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      @NotBlank @Size(max = 16_384) final String line,
      final boolean stderr) {
    emitLogEvent(executionId, nodeId, line);
  }

  /**
   * Emit a log line with the level its plugin gave it, one event per line of the text.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @param stderr true if the line came from the plugin's error stream
   * @param level the level, or {@code null} to have the tracker rate the line
   */
  void emitLogEvent(
      @NotBlank String executionId,
      @NotBlank String nodeId,
      @NotBlank @Size(max = 16_384) String line,
      boolean stderr,
      @Nullable LogLevel level);
}
