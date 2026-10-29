// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonSerialize;

/** Represents the progress of a single node within a workflow DAG. */
public record TaskProgress(
    String nodeId,
    String module,
    String status,
    @JsonSerialize(using = OffsetTimestampSerializer.class) @Schema(nullable = true)
        @Nullable LocalDateTime startTime,
    @JsonSerialize(using = OffsetTimestampSerializer.class) @Schema(nullable = true)
        @Nullable LocalDateTime endTime,
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
