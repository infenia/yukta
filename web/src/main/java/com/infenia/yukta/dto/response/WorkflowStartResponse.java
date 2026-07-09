// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response object for starting a workflow.
 *
 * @param executionId the unique execution identifier
 */
@Schema(description = "Response object for triggering a workflow")
public record WorkflowStartResponse(
    @Schema(
            description = "The unique execution identifier",
            example = "550e8400-e29b-41d4-a716-446655440000")
        String executionId) {}
