// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Event representing a workflow log entry.
 *
 * @param executionId the execution identifier
 * @param nodeId the node identifier
 * @param line the log line
 * @param timestamp when the line was emitted
 * @param stderr true if the line came from the plugin's error stream
 */
public record WorkflowLogEvent(
    String executionId, String nodeId, String line, LocalDateTime timestamp, boolean stderr)
    implements TrackerEvent {

  /**
   * Creates an event for a line from the standard output stream.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param line the log line
   * @param timestamp when the line was emitted
   */
  public WorkflowLogEvent(
      final String executionId,
      final String nodeId,
      final String line,
      final LocalDateTime timestamp) {
    this(executionId, nodeId, line, timestamp, false);
  }

  /**
   * Creates a new WorkflowLogEvent with the current timestamp.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @return a new WorkflowLogEvent
   */
  public static WorkflowLogEvent create(
      final String executionId, final String nodeId, final String line) {
    return new WorkflowLogEvent(
        executionId, nodeId, line, LocalDateTime.now(ZoneId.systemDefault()));
  }

  /**
   * Creates a new WorkflowLogEvent for the given stream with the current timestamp.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @param stderr true if the line came from the plugin's error stream
   * @return a new WorkflowLogEvent
   */
  public static WorkflowLogEvent create(
      final String executionId, final String nodeId, final String line, final boolean stderr) {
    return new WorkflowLogEvent(
        executionId, nodeId, line, LocalDateTime.now(ZoneId.systemDefault()), stderr);
  }
}
