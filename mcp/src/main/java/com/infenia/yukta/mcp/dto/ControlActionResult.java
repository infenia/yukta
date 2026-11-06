// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Result of a workflow or node control action executed via MCP.
 *
 * @param action the action that was executed
 * @param executionId the execution the action targeted, or null for workflow-wide actions
 * @param nodeId the node the action targeted, or null for workflow-level actions
 * @param resultExecutionIds execution IDs produced or affected by the action (e.g. the new
 *     execution ID after a restart, or all stopped execution IDs for stop_all)
 * @param message human-readable summary of the action outcome
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ControlActionResult(
    String action,
    @Nullable String executionId,
    @Nullable String nodeId,
    List<String> resultExecutionIds,
    String message) {

  /** Compact constructor to ensure immutability. */
  public ControlActionResult {
    resultExecutionIds = resultExecutionIds == null ? List.of() : List.copyOf(resultExecutionIds);
  }
}
