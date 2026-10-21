// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import com.infenia.yukta.plugin.core.LogLevel;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * Event representing a workflow log entry.
 *
 * @param executionId the execution identifier
 * @param nodeId the node identifier
 * @param line the log line
 * @param timestamp when the line was emitted
 * @param stderr true if the line came from the plugin's error stream
 * @param level the level the producer gave the line, or {@code null} until the tracker has rated it
 */
public record WorkflowLogEvent(
    String executionId,
    String nodeId,
    String line,
    LocalDateTime timestamp,
    boolean stderr,
    @Nullable LogLevel level)
    implements TrackerEvent {

  /**
   * Creates an event whose level is not set yet.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param line the log line
   * @param timestamp when the line was emitted
   * @param stderr true if the line came from the plugin's error stream
   */
  public WorkflowLogEvent(
      final String executionId,
      final String nodeId,
      final String line,
      final LocalDateTime timestamp,
      final boolean stderr) {
    this(executionId, nodeId, line, timestamp, stderr, null);
  }

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
   * Creates a new WorkflowLogEvent with the current timestamp and the level the producer gave.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier (for plugin resolution)
   * @param line the log line
   * @param stderr true if the line came from the plugin's error stream
   * @param level the producer's level, or {@code null} to have the tracker rate the line
   * @return a new WorkflowLogEvent
   */
  public static WorkflowLogEvent create(
      final String executionId,
      final String nodeId,
      final String line,
      final boolean stderr,
      @Nullable final LogLevel level) {
    return new WorkflowLogEvent(
        executionId, nodeId, line, LocalDateTime.now(ZoneId.systemDefault()), stderr, level);
  }

  /**
   * Returns this event with the given level.
   *
   * @param newLevel the level
   * @return the copy
   */
  public WorkflowLogEvent withLevel(final LogLevel newLevel) {
    return new WorkflowLogEvent(executionId, nodeId, line, timestamp, stderr, newLevel);
  }
}
