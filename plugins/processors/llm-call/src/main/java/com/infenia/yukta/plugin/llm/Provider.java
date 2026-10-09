// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.Locale;

/** Supported LLM providers. */
public enum Provider {
  /** OpenAI and OpenAI-compatible servers (Groq, vLLM, NVIDIA NIM, LM Studio and others). */
  OPENAI,
  /** Anthropic Messages API. */
  ANTHROPIC,
  /** Ollama native API. */
  OLLAMA;

  /**
   * Parse a configuration value, case-insensitively. "openai-compatible" is an alias of OPENAI.
   *
   * @param value the raw configuration value
   * @return the provider
   * @throws IllegalArgumentException if value is null or unknown
   */
  public static Provider from(final Object value) {
    if (value == null) {
      throw new IllegalArgumentException("provider is mandatory");
    }
    final String name = value.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (name) {
      case "OPENAI", "OPENAI_COMPATIBLE" -> OPENAI;
      case "ANTHROPIC" -> ANTHROPIC;
      case "OLLAMA" -> OLLAMA;
      default ->
          throw new IllegalArgumentException(
              "Unknown provider: " + value + " (expected openai, anthropic or ollama)");
    };
  }
}
