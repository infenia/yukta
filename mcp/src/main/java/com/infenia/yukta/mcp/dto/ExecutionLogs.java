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
 */
public record ExecutionLogs(
    String executionId, int totalLines, int returnedLines, List<String> lines) {

  /** Compact constructor to ensure immutability. */
  public ExecutionLogs {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
