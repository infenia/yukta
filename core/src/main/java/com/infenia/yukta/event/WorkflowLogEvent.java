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

/** Event representing a workflow log entry. */
public record WorkflowLogEvent(String executionId, String line, LocalDateTime timestamp) {
  /**
   * Creates a new WorkflowLogEvent with the current timestamp.
   *
   * @param executionId the execution identifier
   * @param line the log line
   * @return a new WorkflowLogEvent
   */
  public static WorkflowLogEvent create(final String executionId, final String line) {
    return new WorkflowLogEvent(executionId, line, LocalDateTime.now(ZoneId.systemDefault()));
  }
}
