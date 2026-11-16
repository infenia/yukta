// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer to a message sent to an agent.
 *
 * @param queued true when the agent cannot take it during its turn, so it waits for the turn to end
 */
@Schema(description = "The answer to a message sent to an agent")
public record ConversationSteerResponse(
    @Schema(description = "Whether the message waits for the end of the turn") boolean queued) {}
