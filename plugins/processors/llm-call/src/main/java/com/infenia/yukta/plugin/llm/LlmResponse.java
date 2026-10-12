// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.List;

/**
 * Raw model answer.
 *
 * @param text the generated text
 * @param inputTokens prompt tokens, 0 when unreported
 * @param outputTokens completion tokens, 0 when unreported
 * @param model the model that answered
 * @param toolCalls the tool calls the model asked for, empty for none
 */
public record LlmResponse(
    String text,
    long inputTokens,
    long outputTokens,
    String model,
    List<ToolCallRequest> toolCalls) {

  /** Defensively copies the tool calls. */
  public LlmResponse {
    toolCalls = List.copyOf(toolCalls);
  }

  /**
   * Creates an answer without tool calls.
   *
   * @param text the generated text
   * @param inputTokens prompt tokens, 0 when unreported
   * @param outputTokens completion tokens, 0 when unreported
   * @param model the model that answered
   */
  public LlmResponse(
      final String text, final long inputTokens, final long outputTokens, final String model) {
    this(text, inputTokens, outputTokens, model, List.of());
  }
}
