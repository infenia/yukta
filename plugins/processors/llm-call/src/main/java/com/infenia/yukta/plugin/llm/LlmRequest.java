// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.time.Duration;
import java.util.List;

/**
 * Fully resolved LLM request.
 *
 * @param provider the provider
 * @param baseUrl the API base URL, or null for the provider default
 * @param apiKey the API key, or null (e.g. local Ollama)
 * @param model the model id
 * @param temperature sampling temperature, or null for the provider default
 * @param maxTokens maximum output tokens, or null for the provider default
 * @param timeout per-call timeout
 * @param system system prompt, or null
 * @param messages conversation turns, oldest first
 */
public record LlmRequest(
    Provider provider,
    String baseUrl,
    String apiKey,
    String model,
    Double temperature,
    Integer maxTokens,
    Duration timeout,
    String system,
    List<ChatTurn> messages) {

  /** Defensively copies the messages. */
  public LlmRequest {
    messages = List.copyOf(messages);
  }
}
