// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The latest settled screen of a terminal agent as text (untrusted: the program wrote it).
 *
 * @param conversationId the conversation
 * @param cols the width
 * @param rows the height
 * @param text the screen, cleaned of control characters and secrets
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentScreen(String conversationId, int cols, int rows, String text) {}
