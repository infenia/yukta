// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Ends a conversation.
 *
 * @param outcome the outcome that overrides the last turn's, or {@code null}
 * @param reason why, or {@code null}
 */
@Schema(description = "Ends a conversation")
public record ConversationEndRequest(
    @Schema(description = "success, failure or incomplete; default the last turn's status")
        @Pattern(
            regexp = "success|failure|incomplete",
            message = "outcome is success, failure or incomplete")
        @Nullable String outcome,
    @Schema(description = "Why it ends")
        @Size(max = 8192, message = "reason is at most 8192 characters")
        @Nullable String reason) {}
