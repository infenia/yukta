// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

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
