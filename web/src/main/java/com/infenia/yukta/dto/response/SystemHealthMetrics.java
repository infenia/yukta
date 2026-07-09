// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Record representing system health metrics.
 *
 * @param threadPoolUtilization the thread pool utilization percentage
 * @param queueDepth the queue depth
 * @param memoryUsedMb the memory used in MB
 * @param memoryMaxMb the maximum memory in MB
 * @param uptime the system uptime
 */
@Schema(description = "Record representing system health metrics")
public record SystemHealthMetrics(
    @Schema(description = "The thread pool utilization percentage", example = "75.5")
        double threadPoolUtilization,
    @Schema(description = "The queue depth", example = "42") int queueDepth,
    @Schema(description = "The memory used in MB", example = "1024") String memoryUsedMb,
    @Schema(description = "The maximum memory in MB", example = "2048") String memoryMaxMb,
    @Schema(description = "The system uptime", example = "2h 30m") String uptime) {}
