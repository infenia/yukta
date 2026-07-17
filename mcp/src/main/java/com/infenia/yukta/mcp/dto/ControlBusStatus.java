// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * Control bus status with monitoring information, returned by the {@code get_control_bus_status}
 * MCP tool.
 *
 * @param activeSessions execution information per session
 * @param pluginRegistry registered plugins and their status
 * @param systemHealth JVM health metrics
 * @param recentExecutions recent execution records across sessions
 */
public record ControlBusStatus(
    List<SessionExecutionInfo> activeSessions,
    List<PluginRegistryEntry> pluginRegistry,
    SystemHealthMetrics systemHealth,
    List<ExecutionRecord> recentExecutions) {

  /** Compact constructor to ensure immutability. */
  public ControlBusStatus {
    activeSessions = activeSessions == null ? List.of() : List.copyOf(activeSessions);
    pluginRegistry = pluginRegistry == null ? List.of() : List.copyOf(pluginRegistry);
    recentExecutions = recentExecutions == null ? List.of() : List.copyOf(recentExecutions);
  }
}
