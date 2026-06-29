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
import java.util.Map;

/** Event representing a change in task status. */
public record TaskStatusEvent(
    String executionId,
    String nodeId,
    String module,
    String status,
    Map<String, Object> metadata,
    LocalDateTime timestamp) {

  /**
   * Canonical constructor for TaskStatusEvent.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   * @param timestamp the event timestamp
   */
  public TaskStatusEvent {
    metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
  }

  /**
   * Creates a new TaskStatusEvent with the current timestamp.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   * @return a new TaskStatusEvent
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public static TaskStatusEvent create(
      final String executionId,
      final String nodeId,
      final String module,
      final String status,
      final Map<String, Object> metadata) {
    return new TaskStatusEvent(
        executionId, nodeId, module, status, metadata, LocalDateTime.now(ZoneId.systemDefault()));
  }
}
