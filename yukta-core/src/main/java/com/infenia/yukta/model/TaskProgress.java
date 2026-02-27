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
package com.infenia.yukta.model;

import java.time.LocalDateTime;
import java.util.Map;

/** Represents the progress of a single node within a workflow DAG. */
public record TaskProgress(
    String nodeId,
    String module,
    String status,
    LocalDateTime startTime,
    LocalDateTime endTime,
    Map<String, Object> metadata) {

  /**
   * Compact constructor to ensure metadata is immutable.
   *
   * @param nodeId node identifier
   * @param module module name
   * @param status task status
   * @param startTime start time
   * @param endTime end time
   * @param metadata task metadata
   */
  public TaskProgress {
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
  }
}
