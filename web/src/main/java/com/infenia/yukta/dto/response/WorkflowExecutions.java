// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Execution history for a single workflow, most recent first.
 *
 * @param executions the execution summaries ordered most recent first
 */
@Schema(description = "Execution history for a single workflow, most recent first")
public record WorkflowExecutions(
    @Schema(description = "The execution summaries ordered most recent first")
        List<WorkflowExecutionSummary> executions) {

  /** Compact constructor to ensure immutability. */
  public WorkflowExecutions {
    executions = executions != null ? List.copyOf(executions) : List.of();
  }
}
