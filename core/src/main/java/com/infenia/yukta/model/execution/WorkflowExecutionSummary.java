// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Summary of a workflow execution.
 *
 * @param executionId the unique execution identifier
 * @param workflowId the workflow identifier
 * @param status the execution status
 * @param startTime the start time of the execution
 * @param endTime the end time of the execution (if finished)
 * @param parentExecutionId the run that started this one as a sub-workflow, or null
 * @param rootExecutionId the top of this run's tree of runs (itself without a parent), or null
 * @param restartOf the run this one restarts, or null
 */
@Schema(description = "Summary of a workflow execution")
public record WorkflowExecutionSummary(
    @Schema(description = "The unique execution identifier") String executionId,
    @Schema(description = "The workflow identifier") String workflowId,
    @Schema(description = "The execution status") String status,
    @Schema(description = "The start time of the execution, with the server's UTC offset")
        @JsonSerialize(using = OffsetTimestampSerializer.class)
        LocalDateTime startTime,
    @Schema(
            description = "The end time of the execution, with the server's UTC offset",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonSerialize(using = OffsetTimestampSerializer.class)
        @Nullable LocalDateTime endTime,
    @Schema(
            description = "The run that started this one as a sub-workflow, if any",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String parentExecutionId,
    @Schema(
            description = "The top of this run's tree of runs (itself when it has no parent)",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String rootExecutionId,
    @Schema(
            description = "The run this one restarts, if any",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String restartOf) {

  /**
   * A summary of a run with no recorded place in a tree of runs.
   *
   * @param executionId the execution identifier
   * @param workflowId the workflow identifier
   * @param status the execution status
   * @param startTime the start time
   * @param endTime the end time
   */
  public WorkflowExecutionSummary(
      final String executionId,
      final String workflowId,
      final String status,
      final LocalDateTime startTime,
      final LocalDateTime endTime) {
    this(executionId, workflowId, status, startTime, endTime, null, null, null);
  }
}
