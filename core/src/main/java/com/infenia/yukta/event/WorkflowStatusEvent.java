// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Event representing a change in workflow status. */
public record WorkflowStatusEvent(String executionId, String status, LocalDateTime timestamp) {
  /**
   * Creates a new WorkflowStatusEvent with the current timestamp.
   *
   * @param executionId the execution identifier
   * @param status the new status
   * @return a new WorkflowStatusEvent
   */
  public static WorkflowStatusEvent create(final String executionId, final String status) {
    return new WorkflowStatusEvent(executionId, status, LocalDateTime.now(ZoneId.systemDefault()));
  }
}
