// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * JVM health metrics, part of {@link ControlBusStatus}.
 *
 * @param threadPoolUtilization the thread utilization percentage
 * @param queueDepth the queue depth
 * @param memoryUsedMb the memory currently used
 * @param memoryMaxMb the maximum available memory
 * @param uptime the JVM uptime
 */
public record SystemHealthMetrics(
    double threadPoolUtilization,
    int queueDepth,
    String memoryUsedMb,
    String memoryMaxMb,
    String uptime) {}
