// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Record representing the control bus status with comprehensive monitoring information.
 *
 * @param activeSessions list of active session execution information
 * @param pluginRegistry list of plugin registry entries
 * @param systemHealth system health metrics
 * @param recentExecutions list of recent execution records
 */
@Schema(
    description =
        "Record representing the control bus status with comprehensive monitoring information")
public record ControlBusStatus(
    @Schema(description = "List of active session execution information")
        List<SessionExecutionInfo> activeSessions,
    @Schema(description = "List of plugin registry entries")
        List<PluginRegistryEntry> pluginRegistry,
    @Schema(description = "System health metrics") SystemHealthMetrics systemHealth,
    @Schema(description = "List of recent execution records")
        List<ExecutionRecord> recentExecutions) {

  /** Compact constructor to ensure immutability of lists. */
  public ControlBusStatus {
    activeSessions = activeSessions != null ? List.copyOf(activeSessions) : List.of();
    pluginRegistry = pluginRegistry != null ? List.copyOf(pluginRegistry) : List.of();
    recentExecutions = recentExecutions != null ? List.copyOf(recentExecutions) : List.of();
  }
}
