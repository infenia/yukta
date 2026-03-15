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
package com.infenia.yukta.model.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Record representing a single execution in the control bus.
 *
 * @param sessionId the session identifier
 * @param executionId the execution identifier
 * @param status the execution status
 * @param duration the execution duration
 */
@Schema(description = "Record representing a single execution in the control bus")
public record ExecutionRecord(
    @Schema(description = "The session identifier", example = "session-123") String sessionId,
    @Schema(description = "The execution identifier", example = "exec-456") String executionId,
    @Schema(
            description = "The execution status",
            example = "COMPLETED",
            allowableValues = {"RUNNING", "COMPLETED", "FAILED"})
        String status,
    @Schema(description = "The execution duration", example = "5000ms") String duration) {}
