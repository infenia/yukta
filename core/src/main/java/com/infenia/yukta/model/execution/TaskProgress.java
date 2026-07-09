// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

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
