// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Keys typed into the terminal of an agent.
 *
 * @param data the bytes, base64; up to 8 KB once decoded
 */
@Schema(description = "Keys typed into the terminal of an agent")
public record TerminalInputRequest(
    @Schema(description = "The bytes typed, base64 encoded; up to 8192 bytes", example = "bHMNCg==")
        @NotBlank(message = "data is required")
        @Size(max = 12_000, message = "data is too long")
        String data) {}
