// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.List;

/**
 * One conversation turn.
 *
 * @param role "user", "assistant" or "tool" (anything else is treated as "user")
 * @param content the turn text; for a "tool" turn the result of the tool
 * @param toolCallId for a "tool" turn, the id of the call it answers
 * @param toolName for a "tool" turn, the tool that was called
 * @param toolCalls for an "assistant" turn, the tool calls the model asked for
 */
public record ChatTurn(
    String role,
    String content,
    String toolCallId,
    String toolName,
    List<ToolCallRequest> toolCalls) {

  /** Defensively copies the tool calls. */
  public ChatTurn {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  /**
   * A plain turn without tool information.
   *
   * @param role "user" or "assistant"
   * @param content the turn text
   */
  public ChatTurn(final String role, final String content) {
    this(role, content, null, null, List.of());
  }
}
