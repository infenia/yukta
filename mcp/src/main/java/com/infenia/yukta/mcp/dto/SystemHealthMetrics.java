// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * JVM health metrics, part of {@link ControlBusStatus}.
 *
 * @param memoryUsedMb the memory currently used
 * @param memoryMaxMb the maximum available memory
 * @param uptime the JVM uptime
 */
public record SystemHealthMetrics(String memoryUsedMb, String memoryMaxMb, String uptime) {}
