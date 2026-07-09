// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.infenia.yukta.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request object for starting a workflow.
 *
 * @param sessionId the session identifier
 * @param workflowId the workflow identifier
 */
@Schema(description = "Request object for starting a workflow")
public record WorkflowStartRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The unique workflow identifier", example = "quality-check") @NotBlank
        String workflowId) {}
