// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;

/** Represents the progress of a workflow. */
public record WorkflowProgress(
    String executionId,
    String sessionId,
    String workflowId,
    String status,
    List<TaskProgress> tasks,
    @JsonSerialize(using = OffsetTimestampSerializer.class) LocalDateTime startTime,
    @JsonSerialize(using = OffsetTimestampSerializer.class) LocalDateTime endTime,
    String controlState,
    String definitionVersion,
    String parentExecutionId,
    String rootExecutionId,
    String restartOf) {

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
   */
  public WorkflowProgress {
    tasks = List.copyOf(tasks);
  }

  /**
   * Creates a progress snapshot of a run with no recorded place in a tree of runs.
   *
   * @param executionId execution identifier
   * @param sessionId session identifier
   * @param workflowId workflow identifier
   * @param status workflow status
   * @param tasks list of task progress
   * @param startTime start time
   * @param endTime end time
   * @param controlState latest operator control state, or null
   * @param definitionVersion content hash of the workflow graph, or null
   */
  public WorkflowProgress(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final List<TaskProgress> tasks,
      final LocalDateTime startTime,
      final LocalDateTime endTime,
      final String controlState,
      final String definitionVersion) {
    this(
        executionId,
        sessionId,
        workflowId,
        status,
        tasks,
        startTime,
        endTime,
        controlState,
        definitionVersion,
        null,
        null,
        null);
  }

  /**
   * Creates a progress snapshot without a control state.
   *
   * @param executionId execution identifier
   * @param sessionId session identifier
   * @param workflowId workflow identifier
   * @param status workflow status
   * @param tasks list of task progress
   * @param startTime start time
   * @param endTime end time
   */
  public WorkflowProgress(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final List<TaskProgress> tasks,
      final LocalDateTime startTime,
      final LocalDateTime endTime) {
    this(executionId, sessionId, workflowId, status, tasks, startTime, endTime, null, null);
  }

  /**
   * Creates a progress snapshot without a definition version.
   *
   * @param executionId execution identifier
   * @param sessionId session identifier
   * @param workflowId workflow identifier
   * @param status workflow status
   * @param tasks list of task progress
   * @param startTime start time
   * @param endTime end time
   * @param controlState latest operator control state, or null
   */
  public WorkflowProgress(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final List<TaskProgress> tasks,
      final LocalDateTime startTime,
      final LocalDateTime endTime,
      final String controlState) {
    this(executionId, sessionId, workflowId, status, tasks, startTime, endTime, controlState, null);
  }
}
