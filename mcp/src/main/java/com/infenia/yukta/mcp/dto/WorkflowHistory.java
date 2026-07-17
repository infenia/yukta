// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import java.util.List;

/**
 * Execution history of a session returned by the {@code get_workflow_history} MCP tool. Wrapping
 * the list in a record keeps the tool's output schema generatable (bare {@code Mono<List<T>>}
 * return types break schema generation on the async stateless MCP stack).
 *
 * @param executions the execution summaries of the session
 */
public record WorkflowHistory(List<WorkflowExecutionSummary> executions) {

  /** Compact constructor to ensure immutability. */
  public WorkflowHistory {
    executions = executions == null ? List.of() : List.copyOf(executions);
  }
}
