// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * A message to an agent. The size limit is {@code yukta.agents.max-message-bytes}.
 *
 * @param text the message
 * @param interrupt true to stop the agent's turn first
 */
@Schema(description = "A message to an agent")
public record ConversationMessageRequest(
    @Schema(description = "The message", example = "Also update the README")
        @NotBlank(message = "text is required")
        String text,
    @Schema(description = "Stop the agent's current turn first") boolean interrupt) {}
