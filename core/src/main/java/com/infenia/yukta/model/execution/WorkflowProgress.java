// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Represents the progress of a workflow. */
public record WorkflowProgress(
    String executionId,
    String sessionId,
    String workflowId,
    TaskStatus status,
    List<TaskProgress> tasks,
    Instant startTime,
    @Schema(nullable = true) @Nullable Instant endTime,
    @Schema(nullable = true) @Nullable String controlState,
    @Schema(nullable = true) @Nullable String definitionVersion,
    @Schema(nullable = true) @Nullable String parentExecutionId,
    @Schema(nullable = true) @Nullable String rootExecutionId,
    @Schema(nullable = true) @Nullable String restartOf,
    @Schema(nullable = true) @Nullable String traceId) {

  /**
   * Compact constructor to ensure immutability.
   *
   * @param executionId execution identifier
   * @param sessionId session identifier
   * @param workflowId workflow identifier
   * @param status workflow status
   * @param tasks list of task progress
   * @param startTime start time
   * @param endTime end time
   * @param controlState latest operator control state (see {@link ControlState}), or null
   * @param definitionVersion content hash of the workflow graph this run uses, or null if unknown
   * @param parentExecutionId the run that started this one as a sub-workflow, or null
   * @param rootExecutionId the top of this run's tree of runs (itself without a parent), or null
   * @param restartOf the run this one restarts, or null
   * @param traceId trace id of the run's span when a tracer is configured, or null
   */
  public WorkflowProgress {
    tasks = List.copyOf(tasks);
  }
}
