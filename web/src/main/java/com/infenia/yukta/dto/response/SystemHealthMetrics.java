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
