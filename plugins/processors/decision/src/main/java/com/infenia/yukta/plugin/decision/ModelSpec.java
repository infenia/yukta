// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Which model answers, and how to reach it.
 *
 * @param backend how the model is called
 * @param provider for {@link Backend#LLM}: {@code openai}, {@code anthropic} or {@code ollama}
 * @param model the model name
 * @param baseUrl the endpoint, or {@code null} for the default
 * @param apiKey the API key, or {@code null}
 * @param timeout how long to wait for an answer
 */
public record ModelSpec(
    Backend backend,
    @Nullable String provider,
    String model,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    Duration timeout) {

  /** How a model is called. */
  public enum Backend {
    /** Ollama's System One endpoint: fast, constrained, calibrated probabilities. */
    SYSTEMONE,
    /** Any chat model (OpenAI-compatible, Anthropic, Ollama) prompted to answer as JSON. */
    LLM
  }
}
