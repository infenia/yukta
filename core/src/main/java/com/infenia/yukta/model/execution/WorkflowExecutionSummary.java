// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * Summary of a workflow execution.
 *
 * @param executionId the unique execution identifier
 * @param workflowId the workflow identifier
 * @param status the execution status
 * @param startTime the start time of the execution
 * @param endTime the end time of the execution (if finished)
 */
@Schema(description = "Summary of a workflow execution")
public record WorkflowExecutionSummary(
    @Schema(description = "The unique execution identifier") String executionId,
    @Schema(description = "The workflow identifier") String workflowId,
    @Schema(description = "The execution status") String status,
    @Schema(description = "The start time of the execution") LocalDateTime startTime,
    @Schema(description = "The end time of the execution") LocalDateTime endTime) {}
