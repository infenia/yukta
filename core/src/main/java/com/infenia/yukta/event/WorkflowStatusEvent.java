/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
