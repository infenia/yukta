// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the state of a workflow execution; written by the event thread, read by requests. */
@SuppressWarnings({"PMD.AvoidUsingVolatile", "PMD.OnlyOneReturn"})
final class WorkflowState {
  /** The execution identifier. */
  /* default */ final String executionId;

  /** The session identifier. */
  /* default */ final String sessionId;

  /** The workflow identifier. */
  /* default */ final String workflowId;

  /** The current execution status. */
  /* default */ volatile String status;

  /** The latest operator control state, or null. */
  /* default */ volatile String controlState;

  /** The definition version (graph hash) this run uses, or null until recorded. */
  /* default */ volatile String definitionVersion;

  /** Map of task progress by node ID. */
  /* default */ final Map<String, TaskProgress> taskMap;

  /** The start time of the execution. */
  /* default */ final LocalDateTime startTime;

  /** The end time of the execution. */
  /* default */ volatile LocalDateTime endTime;

  /* default */ WorkflowState(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final List<String> nodeIds,
      final LocalDateTime startTime) {
    this.executionId = executionId;
    this.sessionId = sessionId;
    this.workflowId = workflowId;
    this.status = status;
    this.taskMap = Collections.synchronizedMap(new LinkedHashMap<>(nodeIds.size()));
    nodeIds.forEach(
        nodeId ->
            taskMap.put(
                nodeId,
                new TaskProgress(nodeId, "", "PENDING", null, null, Collections.emptyMap())));
    this.startTime = startTime;
  }

  /* default */ void updateTask(
      final String nodeId,
      final String module,
      final String status,
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
  /* default */ void finalizeTasks(final String runStatus) {
    final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
    taskMap.replaceAll(
        (_, task) -> {
          if (TaskStatus.isTerminalName(task.status())) {
            return task;
          }
          final TaskStatus finalStatus = finalStatusFor(task.status(), runStatus);
          return new TaskProgress(
              task.nodeId(),
              task.module(),
              finalStatus.name(),
              task.startTime(),
              now,
              task.metadata());
        });
  }

  private static TaskStatus finalStatusFor(final String taskStatus, final String runStatus) {
    if (!TaskStatus.PENDING.name().equals(taskStatus)) {
      return TaskStatus.CANCELLED;
    }
    return switch (TaskStatus.valueOf(runStatus)) {
      case SUCCESS -> TaskStatus.SKIPPED;
      case FAILURE, ERROR, TIMED_OUT -> TaskStatus.UPSTREAM_FAILED;
      default -> TaskStatus.CANCELLED;
    };
  }

  /* default */ Map<String, String> nodeStatuses() {
    final Map<String, String> statuses = new ConcurrentHashMap<>();
    taskMap.forEach((nodeId, task) -> statuses.put(nodeId, task.status()));
    return statuses;
  }

  /* default */ List<TaskProgress> getTasks() {
    return List.copyOf(taskMap.values());
  }

  /** Whether a node in status {@code current} may be reported as {@code next}. */
  /* default */ static boolean mayMoveTo(final String current, final String next) {
    return current.equals(next)
        || (!TaskStatus.isTerminalName(current) && rank(next) >= rank(current));
  }

  private static int rank(final String status) {
    return switch (status) {
      case "PENDING" -> 0;
      case "QUEUED" -> 1;
      case "RUNNING" -> 2;
      default -> 3;
    };
  }

  private TaskProgress createUpdatedTask(
      final TaskProgress current,
      final String module,
      final String status,
      final Map<String, Object> metadata) {
    final LocalDateTime taskStartTime = determineStartTime(current.startTime(), status);
    final LocalDateTime taskEndTime = determineEndTime(current.endTime(), status);
    final Map<String, Object> newMetadata = mergeMetadata(current.metadata(), metadata);

    return new TaskProgress(
        current.nodeId(), module, status, taskStartTime, taskEndTime, Map.copyOf(newMetadata));
  }

  private LocalDateTime determineStartTime(final LocalDateTime current, final String status) {
    return ("RUNNING".equals(status) && current == null)
        ? LocalDateTime.now(ZoneId.systemDefault())
        : current;
  }

  private LocalDateTime determineEndTime(final LocalDateTime current, final String status) {
    return (TaskStatus.isTerminalName(status) && current == null)
        ? LocalDateTime.now(ZoneId.systemDefault())
        : current;
  }

  private Map<String, Object> mergeMetadata(
      final Map<String, Object> current, final Map<String, Object> additional) {
    final Map<String, Object> merged = new ConcurrentHashMap<>(current);
    if (additional != null) {
      merged.putAll(additional);
    }
    return merged;
  }

  /* default */ void setStatus(final String status) {
    this.status = status;
  }

  /* default */ void setControlState(final String controlState) {
    this.controlState = controlState;
  }

  /* default */ void setEndTime(final LocalDateTime endTime) {
    this.endTime = endTime;
  }
}
