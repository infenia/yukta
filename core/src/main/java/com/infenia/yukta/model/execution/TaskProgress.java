// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Represents the progress of a single node within a workflow DAG. */
public record TaskProgress(
    String nodeId,
    String module,
    TaskStatus status,
    @Schema(nullable = true) @Nullable Instant startTime,
    @Schema(nullable = true) @Nullable Instant endTime,
    Map<String, Object> metadata,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Breakpoint breakpoint) {

  /**
   * A condition that pauses the node: when the value at {@code field} equals {@code equals}.
   *
   * @param field {@code payload}, {@code payload.a.b} or {@code metadata.key}
   * @param equals the text the value must equal
   */
  public record Breakpoint(String field, String equals) {}

  /**
   * A node's progress without a breakpoint.
   *
   * @param nodeId node identifier
   * @param module module name
   * @param status task status
   * @param startTime start time
   * @param endTime end time
   * @param metadata task metadata
   */
  public TaskProgress(
      final String nodeId,
      final String module,
      final TaskStatus status,
      final @Nullable Instant startTime,
      final @Nullable Instant endTime,
      final Map<String, Object> metadata) {
    this(nodeId, module, status, startTime, endTime, metadata, null);
  }

  /**
   * Compact constructor to ensure metadata is immutable.
   *
   * @param nodeId node identifier
   * @param module module name
   * @param status task status
   * @param startTime start time
   * @param endTime end time
   * @param metadata task metadata
   * @param breakpoint the active breakpoint, or null
   */
  public TaskProgress {
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
  }
}
