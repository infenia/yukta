// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** The finished executions a tracker remembers for each session. */
final class ExecutionHistory {

  /** Finished executions kept per session for history; older ones are dropped on cleanup. */
  /* default */ static final int MAX_FINISHED_PER_SESSION = 100;

  /** Per session, execution IDs that were cleaned up, oldest first. */
  private final Map<String, Deque<String>> finishedBySession = new ConcurrentHashMap<>();

  /** Session ID to its workflow states. */
  private final Map<String, Map<String, WorkflowState>> sessionStates;

  /** Execution ID to its workflow state. */
  private final Map<String, WorkflowState> executionIndex;

  /* default */ ExecutionHistory(
      final Map<String, Map<String, WorkflowState>> sessionStates,
      final Map<String, WorkflowState> executionIndex) {
    this.sessionStates = sessionStates;
    this.executionIndex = executionIndex;
  }

  /** Newest first, optionally limited to the executions that match. */
  /* default */ List<WorkflowExecutionSummary> summaries(
      final String sessionId, final Predicate<WorkflowState> filter) {
    final Map<String, WorkflowState> states = sessionStates.get(sessionId);
    List<WorkflowExecutionSummary> history = Collections.emptyList();
    if (states != null) {
      history =
          states.values().stream()
              .filter(filter)
              .map(
                  s ->
                      new WorkflowExecutionSummary(
                          s.executionId, s.workflowId, s.status, s.startTime, s.endTime))
              .sorted((a, b) -> b.startTime().compareTo(a.startTime()))
              .toList();
    }
    return history;
  }

  /** Puts a run that ended before this server started back into history. */
  /* default */ void restore(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final Map<String, String> nodeStatuses,
      final Instant started,
      final Instant ended,
      final String definitionVersion) {
    final WorkflowState state =
        new WorkflowState(
            executionId,
            sessionId,
            workflowId,
            status,
            List.copyOf(nodeStatuses.keySet()),
            LocalDateTime.ofInstant(started, ZoneId.systemDefault()));
    nodeStatuses.forEach(
        (nodeId, nodeStatus) -> state.updateTask(nodeId, "", nodeStatus, Map.of()));
    state.setEndTime(LocalDateTime.ofInstant(ended, ZoneId.systemDefault()));
    state.definitionVersion = definitionVersion;
    sessionStates
        .computeIfAbsent(sessionId, _ -> new ConcurrentHashMap<>())
        .put(executionId, state);
    executionIndex.put(executionId, state);
  }

  /** Forgets a session's history. */
  /* default */ void forget(final String sessionId) {
    finishedBySession.remove(sessionId);
  }

  /** Remembers a cleaned-up execution and drops the oldest ones beyond the per-session cap. */
  @SuppressWarnings("PMD.AvoidSynchronizedStatement")
  /* default */ void trim(final WorkflowState state) {
    final Map<String, WorkflowState> states = sessionStates.get(state.sessionId);
    if (states == null) {
      return;
    }
    final Deque<String> finished =
        finishedBySession.computeIfAbsent(state.sessionId, _ -> new ArrayDeque<>());
    // Cleanups of one session can run concurrently, so the queue is guarded by its own lock.
    synchronized (finished) {
      finished.addLast(state.executionId);
      while (finished.size() > MAX_FINISHED_PER_SESSION) {
        states.remove(finished.removeFirst());
      }
    }
  }
}
