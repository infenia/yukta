// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the state of a workflow execution; written by the event thread, read by requests. */
@SuppressWarnings("PMD.AvoidUsingVolatile")
final class WorkflowState {
  /** The execution identifier. */
  /* default */ final String executionId;

  /** The session identifier. */
  /* default */ final String sessionId;

  /** The workflow identifier. */
  /* default */ final String workflowId;

  /** The current execution status. */
  /* default */ volatile TaskStatus status;

  /** The latest operator control state, or null. */
  /* default */ volatile String controlState;

  /** The definition version (graph hash) this run uses, or null until recorded. */
  /* default */ volatile String definitionVersion;

  /** The run that started this one as a sub-workflow, or null. */
  /* default */ volatile String parentExecutionId;

  /** The top of this run's tree of runs (itself when it has no parent), or null until recorded. */
  /* default */ volatile String rootExecutionId;

  /** The run this one restarts, or null. */
  /* default */ volatile String restartOf;

  /** Who or what started the run, or null when not known. */
  /* default */ volatile String initiator;

  /** Map of task progress by node ID. */
  /* default */ final Map<String, TaskProgress> taskMap;

  /** The start time of the execution. */
  /* default */ final Instant startTime;

  /** The end time of the execution. */
  /* default */ volatile Instant endTime;

  /* default */ WorkflowState(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final TaskStatus status,
      final List<String> nodeIds,
      final Instant startTime) {
    this.executionId = executionId;
    this.sessionId = sessionId;
    this.workflowId = workflowId;
    this.status = status;
    this.taskMap = Collections.synchronizedMap(new LinkedHashMap<>(nodeIds.size()));
    nodeIds.forEach(
        nodeId ->
            taskMap.put(
                nodeId,
                new TaskProgress(
                    nodeId, "", TaskStatus.PENDING, null, null, Collections.emptyMap())));
    this.startTime = startTime;
  }

  /* default */ void updateTask(
      final String nodeId,
      final String module,
      final TaskStatus status,
      final Map<String, Object> metadata) {
    taskMap.compute(
        nodeId,
        (_, old) -> {
          if (old == null) {
            return null;
          }
          // A node only moves forward: a late event never reopens an ended node or steps back,
          // though what it reports is still merged.
          return createUpdatedTask(
              old, module, mayMoveTo(old.status(), status) ? status : old.status(), metadata);
        });
  }

  /**
   * Gives every node that has not reached a terminal status its final one, once the run has ended.
   * A running node was cancelled; a node that never started was skipped when the run succeeded,
   * never reached when it failed, and cancelled otherwise.
   */
  /* default */ void finalizeTasks(final TaskStatus runStatus) {
    final Instant now = Instant.now();
    taskMap.replaceAll(
        (_, task) -> {
          if (task.status().isTerminal()) {
            return task;
          }
          final TaskStatus finalStatus = finalStatusFor(task.status(), runStatus);
          return new TaskProgress(
              task.nodeId(),
              task.module(),
              finalStatus,
              task.startTime(),
              now,
              task.metadata(),
              task.breakpoint());
        });
  }

  private static TaskStatus finalStatusFor(
      final TaskStatus taskStatus, final TaskStatus runStatus) {
    if (taskStatus != TaskStatus.PENDING) {
      return TaskStatus.CANCELLED;
    }
    return switch (runStatus) {
      case SUCCESS -> TaskStatus.SKIPPED;
      case FAILURE, ERROR, TIMED_OUT -> TaskStatus.UPSTREAM_FAILED;
      default -> TaskStatus.CANCELLED;
    };
  }

  /* default */ Map<String, TaskStatus> nodeStatuses() {
    final Map<String, TaskStatus> statuses = new ConcurrentHashMap<>();
    taskMap.forEach((nodeId, task) -> statuses.put(nodeId, task.status()));
    return statuses;
  }

  /* default */ List<TaskProgress> getTasks() {
    return List.copyOf(taskMap.values());
  }

  /** Whether a node in status {@code current} may be reported as {@code next}. */
  /* default */ static boolean mayMoveTo(final TaskStatus current, final TaskStatus next) {
    return current == next || (!current.isTerminal() && rank(next) >= rank(current));
  }

  private static int rank(final TaskStatus status) {
    return switch (status) {
      case PENDING -> 0;
      case QUEUED -> 1;
      case RUNNING -> 2;
      default -> 3;
    };
  }

  private TaskProgress createUpdatedTask(
      final TaskProgress current,
      final String module,
      final TaskStatus status,
      final Map<String, Object> metadata) {
    final Instant taskStartTime = determineStartTime(current.startTime(), status);
    final Instant taskEndTime = determineEndTime(current.endTime(), status);
    final Map<String, Object> newMetadata = mergeMetadata(current.metadata(), metadata);

    return new TaskProgress(
        current.nodeId(),
        module,
        status,
        taskStartTime,
        taskEndTime,
        Map.copyOf(newMetadata),
        current.breakpoint());
  }

  private Instant determineStartTime(final Instant current, final TaskStatus status) {
    return (status == TaskStatus.RUNNING && current == null) ? Instant.now() : current;
  }

  private Instant determineEndTime(final Instant current, final TaskStatus status) {
    return (status.isTerminal() && current == null) ? Instant.now() : current;
  }

  private Map<String, Object> mergeMetadata(
      final Map<String, Object> current, final Map<String, Object> additional) {
    final Map<String, Object> merged = new ConcurrentHashMap<>(current);
    if (additional != null) {
      merged.putAll(additional);
    }
    return merged;
  }

  /* default */ void setBreakpoint(final String nodeId, final TaskProgress.Breakpoint breakpoint) {
    taskMap.computeIfPresent(
        nodeId,
        (_, t) ->
            new TaskProgress(
                t.nodeId(),
                t.module(),
                t.status(),
                t.startTime(),
                t.endTime(),
                t.metadata(),
                breakpoint));
  }

  /* default */ void setStatus(final TaskStatus status) {
    this.status = status;
  }

  /* default */ void setControlState(final String controlState) {
    this.controlState = controlState;
  }

  /* default */ void setEndTime(final Instant endTime) {
    this.endTime = endTime;
  }
}
