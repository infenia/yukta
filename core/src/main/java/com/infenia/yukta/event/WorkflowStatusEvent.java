// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import com.infenia.yukta.model.execution.TaskStatus;
import java.time.Instant;

/** Event representing a change in workflow status. */
public record WorkflowStatusEvent(String executionId, TaskStatus status, Instant timestamp)
    implements TrackerEvent {
  /**
   * Creates a new WorkflowStatusEvent with the current timestamp.
   *
   * @param executionId the execution identifier
   * @param status the new status
   * @return a new WorkflowStatusEvent
   */
  public static WorkflowStatusEvent create(final String executionId, final TaskStatus status) {
    return new WorkflowStatusEvent(executionId, status, Instant.now());
  }
}
