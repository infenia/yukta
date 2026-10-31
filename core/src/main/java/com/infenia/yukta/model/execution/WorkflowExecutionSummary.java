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
 * @param nodes how far the run got through its nodes, or null when not known
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
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        @JsonSerialize(using = OffsetTimestampSerializer.class)
        @Nullable LocalDateTime endTime,
    @Schema(
            description = "The run that started this one as a sub-workflow, if any",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        @Nullable String parentExecutionId,
    @Schema(
            description = "The top of this run's tree of runs (itself when it has no parent)",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        @Nullable String rootExecutionId,
    @Schema(
            description = "The run this one restarts, if any",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        @Nullable String restartOf,
    @Schema(
            description = "How far the run got through its nodes",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        @Nullable Nodes nodes,
    @Schema(
            description =
                "Who or what started the run (a name given at the start, or the key that started"
                    + " it), if known",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        @Nullable String initiator) {

  /** A summary of a run whose initiator is not known. */
  public WorkflowExecutionSummary(
      final String executionId,
      final String workflowId,
      final String status,
      final LocalDateTime startTime,
      final LocalDateTime endTime,
      final String parentExecutionId,
      final String rootExecutionId,
      final String restartOf,
      final Nodes nodes) {
    this(
        executionId,
        workflowId,
        status,
        startTime,
        endTime,
        parentExecutionId,
        rootExecutionId,
        restartOf,
        nodes,
        null);
  }

  /**
   * A summary of a run in a tree of runs, without its node progress.
   *
   * @param executionId the execution identifier
   * @param workflowId the workflow identifier
   * @param status the execution status
   * @param startTime the start time
   * @param endTime the end time
   * @param parentExecutionId the run that started this one, or null
   * @param rootExecutionId the top of its tree of runs, or null
   * @param restartOf the run it restarts, or null
   */
  public WorkflowExecutionSummary(
      final String executionId,
      final String workflowId,
      final String status,
      final LocalDateTime startTime,
      final LocalDateTime endTime,
      final String parentExecutionId,
      final String rootExecutionId,
      final String restartOf) {
    this(
        executionId,
        workflowId,
        status,
        startTime,
        endTime,
        parentExecutionId,
        rootExecutionId,
        restartOf,
        null);
  }

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
    this(executionId, workflowId, status, startTime, endTime, null, null, null, null);
  }

  /**
   * How far a run got through its nodes.
   *
   * @param done nodes in a final state (succeeded, failed, skipped, stopped…)
   * @param total nodes in the run
   * @param failed the first node that failed, errored or timed out, or null
   */
  @Schema(description = "How far a run got through its nodes")
  public record Nodes(
      @Schema(description = "Nodes in a final state") int done,
      @Schema(description = "Nodes in the run") int total,
      @Schema(description = "The first node that failed, if any", nullable = true)
          @Nullable String failed) {}
}
