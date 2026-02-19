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
package com.infenia.jagratha.model;

import java.time.LocalDateTime;
import java.util.List;

/** Represents the progress of a workflow. */
public record WorkflowProgress(
    String sessionId,
    String status,
    List<TaskProgress> tasks,
    LocalDateTime startTime,
    LocalDateTime endTime) {

  /**
   * Compact constructor to ensure immutability.
   *
   * @param sessionId session identifier
   * @param status workflow status
   * @param tasks list of task progress
   * @param startTime start time
   * @param endTime end time
   */
  public WorkflowProgress {
    tasks = List.copyOf(tasks);
  }
}
