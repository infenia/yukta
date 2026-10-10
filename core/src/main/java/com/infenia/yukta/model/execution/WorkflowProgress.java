// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import java.time.LocalDateTime;
import java.util.List;

/** Represents the progress of a workflow. */
public record WorkflowProgress(
    String executionId,
    String sessionId,
    String workflowId,
    String status,
    List<TaskProgress> tasks,
    LocalDateTime startTime,
    LocalDateTime endTime,
    String controlState) {

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
   */
  public WorkflowProgress {
    tasks = List.copyOf(tasks);
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
    this(executionId, sessionId, workflowId, status, tasks, startTime, endTime, null);
  }
}
