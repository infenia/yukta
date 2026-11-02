// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Hands an input request up to the next tier.
 *
 * @param reason why the caller does not answer it, at most 8 KB
 * @param advice what the caller suggests to whoever answers next, at most 8 KB
 */
@Schema(description = "Hands an input request up to the next tier")
public record InputEscalateRequest(
    @Schema(description = "Why it is handed up", example = "Needs a person who owns the budget")
        @NotBlank(message = "reason is required")
        @Size(max = 8192, message = "reason is at most 8192 characters")
        String reason,
    @Schema(description = "What the caller suggests to whoever answers next")
        @Size(max = 8192, message = "advice is at most 8192 characters")
        @Nullable String advice) {}
