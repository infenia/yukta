// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Event representing a workflow log entry. */
public record WorkflowLogEvent(
    String executionId, String nodeId, String line, LocalDateTime timestamp) {
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
}
