// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Record representing execution information for a session.
 *
 * @param sessionId the session identifier
 * @param activeExecutions the number of active executions
 * @param totalWorkflows the total number of workflows
 */
@Schema(description = "Record representing execution information for a session")
public record SessionExecutionInfo(
    @Schema(description = "The session identifier", example = "session-123") String sessionId,
    @Schema(description = "The number of active executions", example = "5") int activeExecutions,
    @Schema(description = "The total number of workflows", example = "10") int totalWorkflows) {}
