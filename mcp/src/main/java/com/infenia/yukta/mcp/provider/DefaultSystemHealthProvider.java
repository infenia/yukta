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
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.dto.response.ControlBusStatus;
import com.infenia.yukta.dto.response.ExecutionRecord;
import com.infenia.yukta.dto.response.PluginRegistryEntry;
import com.infenia.yukta.dto.response.SessionExecutionInfo;
import com.infenia.yukta.dto.response.SystemHealthMetrics;
import com.infenia.yukta.mcp.util.UptimeFormatter;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default implementation of SystemHealthProvider. Handles system health monitoring, control bus
 * status reporting, and metrics collection.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.DoNotUseThreads")
public class DefaultSystemHealthProvider implements SystemHealthProvider {

  /** Registry for accessing all available plugins. */
  private final PluginRegistry registry;

  @Override
  public ControlBusStatus getControlBusStatus(final String filterType) {
    final List<SessionExecutionInfo> activeSessions =
        filterType == null || "sessions".equalsIgnoreCase(filterType)
            ? buildSessionExecutionInfo()
            : List.of();

    final List<PluginRegistryEntry> pluginRegistry =
        filterType == null || "plugins".equalsIgnoreCase(filterType)
            ? buildPluginRegistryInfo()
            : List.of();

    final SystemHealthMetrics systemHealth =
        filterType == null || "health".equalsIgnoreCase(filterType)
            ? buildSystemHealthMetrics()
            : new SystemHealthMetrics(0, 0, "0", "0", "0s");

    final List<ExecutionRecord> recentExecutions =
        filterType == null || "executions".equalsIgnoreCase(filterType)
            ? buildRecentExecutions()
            : List.of();

    return new ControlBusStatus(activeSessions, pluginRegistry, systemHealth, recentExecutions);
  }

  private List<SessionExecutionInfo> buildSessionExecutionInfo() {
    return List.of();
  }

  private List<PluginRegistryEntry> buildPluginRegistryInfo() {
    return registry.listPlugins().stream()
        .map(p -> new PluginRegistryEntry(p.getType(), p.getCategory().toString(), "ACTIVE"))
        .toList();
  }

  @SuppressWarnings("PMD.LongVariable")
  private SystemHealthMetrics buildSystemHealthMetrics() {
    final Runtime runtime = Runtime.getRuntime();
    final long maxMemory = runtime.maxMemory() / (1024 * 1024);
    final long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    final double threadPoolUtilization =
        (Thread.activeCount() / (double) runtime.availableProcessors()) * 100;

    return new SystemHealthMetrics(
        threadPoolUtilization,
        0,
        usedMemory + " MB",
        maxMemory + " MB",
        UptimeFormatter.getSystemUptime());
  }

  private List<ExecutionRecord> buildRecentExecutions() {
    return new ArrayList<>();
  }
}
