// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * Logs of a workflow execution returned by the {@code get_execution_logs} MCP tool.
 *
 * @param executionId the execution the logs belong to
 * @param totalLines number of log lines matching the filter before tail truncation
 * @param returnedLines number of log lines actually returned
 * @param lines the formatted log lines
 * @param nextCursor number of log entries stored so far, matching the filter or not; pass it as
 *     {@code afterCursor} to get only the entries written after this call
 */
public record ExecutionLogs(
    String executionId, int totalLines, int returnedLines, List<String> lines, int nextCursor) {

  /**
   * Creates logs whose cursor is the number of lines returned.
   *
   * @param executionId the execution the logs belong to
   * @param totalLines number of log lines matching the filter before tail truncation
   * @param returnedLines number of log lines actually returned
   * @param lines the formatted log lines
   */
  public ExecutionLogs(
      final String executionId,
      final int totalLines,
      final int returnedLines,
      final List<String> lines) {
    this(executionId, totalLines, returnedLines, lines, totalLines);
  }

  /** Compact constructor to ensure immutability. */
  public ExecutionLogs {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
