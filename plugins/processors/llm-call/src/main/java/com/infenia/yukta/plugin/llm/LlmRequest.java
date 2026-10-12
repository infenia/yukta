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
 * @param tools tools the model may ask to call, empty for none
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
    List<ChatTurn> messages,
    List<ToolSpec> tools) {

  /** Defensively copies the messages and tools. */
  public LlmRequest {
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
  }

  /**
   * Creates a request without tools.
   *
   * @param provider the provider
   * @param baseUrl the API base URL, or null for the provider default
   * @param apiKey the API key, or null
   * @param model the model id
   * @param temperature sampling temperature, or null
   * @param maxTokens maximum output tokens, or null
   * @param timeout per-call timeout
   * @param system system prompt, or null
   * @param messages conversation turns, oldest first
   */
  public LlmRequest(
      final Provider provider,
      final String baseUrl,
      final String apiKey,
      final String model,
      final Double temperature,
      final Integer maxTokens,
      final Duration timeout,
      final String system,
      final List<ChatTurn> messages) {
    this(
        provider,
        baseUrl,
        apiKey,
        model,
        temperature,
        maxTokens,
        timeout,
        system,
        messages,
        List.of());
  }
}
