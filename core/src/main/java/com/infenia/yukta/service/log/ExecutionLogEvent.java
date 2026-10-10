// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the append-only execution log.
 *
 * @param type what happened
 * @param executionId the execution
 * @param sessionId the session that owns it
 * @param workflowId the workflow it runs
 * @param definitionVersion content hash of the graph (set on {@code RUN_STARTED})
 * @param status the final status (set on {@code RUN_FINISHED} and {@code RUN_ABORTED})
 * @param nodeStatuses final status of each node (set on {@code RUN_FINISHED})
 * @param timestamp when it happened
 */
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public record ExecutionLogEvent(
    Type type,
    String executionId,
    String sessionId,
    String workflowId,
    @Nullable String definitionVersion,
    @Nullable String status,
    Map<String, String> nodeStatuses,
    Instant timestamp) {

  /** Kind of log entry. */
  public enum Type {
    RUN_STARTED,
    RUN_FINISHED,
    RUN_ABORTED
  }

  /**
   * Keeps an immutable copy of the node statuses.
   *
   * @param type what happened
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param definitionVersion content hash of the graph
   * @param status the final status
   * @param nodeStatuses final status of each node
   * @param timestamp when it happened
   */
  public ExecutionLogEvent {
    nodeStatuses = nodeStatuses == null ? Map.of() : Map.copyOf(nodeStatuses);
  }

  /**
   * A run has started.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param definitionVersion content hash of the graph
   * @return the event
   */
  public static ExecutionLogEvent started(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String definitionVersion) {
    return new ExecutionLogEvent(
        Type.RUN_STARTED,
        executionId,
        sessionId,
        workflowId,
        definitionVersion,
        null,
        Map.of(),
        Instant.now());
  }

  /**
   * A run has reached a terminal status.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param status the final status
   * @param nodeStatuses final status of each node
   * @return the event
   */
  public static ExecutionLogEvent finished(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final Map<String, String> nodeStatuses) {
    return new ExecutionLogEvent(
        Type.RUN_FINISHED,
        executionId,
        sessionId,
        workflowId,
        null,
        status,
        nodeStatuses,
        Instant.now());
  }

  /**
   * A run that never finished was found after a restart of the server.
   *
   * @param started the run's start event
   * @return the event
   */
  public static ExecutionLogEvent aborted(final ExecutionLogEvent started) {
    return new ExecutionLogEvent(
        Type.RUN_ABORTED,
        started.executionId(),
        started.sessionId(),
        started.workflowId(),
        null,
        "ABORTED",
        Map.of(),
        Instant.now());
  }
}
