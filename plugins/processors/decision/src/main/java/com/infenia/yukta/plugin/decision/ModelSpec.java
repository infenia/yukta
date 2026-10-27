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
 * @param keepAlive for {@link Backend#SYSTEMONE}: how long Ollama keeps the model loaded, such as
 *     {@code 30m} or {@code -1}, or {@code null} to leave it to Ollama
 * @param numCtx for {@link Backend#SYSTEMONE}: the context size to load the model with, or {@code
 *     null} to leave it to Ollama
 */
public record ModelSpec(
    Backend backend,
    @Nullable String provider,
    String model,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    Duration timeout,
    @Nullable String keepAlive,
    @Nullable Integer numCtx) {

  /**
   * A spec with Ollama left to decide how long the model stays loaded and how big its context is.
   */
  public ModelSpec(
      final Backend backend,
      final @Nullable String provider,
      final String model,
      final @Nullable String baseUrl,
      final @Nullable String apiKey,
      final Duration timeout) {
    this(backend, provider, model, baseUrl, apiKey, timeout, null, null);
  }

  /** How a model is called. */
  public enum Backend {
    /** Ollama's System One endpoint: fast, constrained, calibrated probabilities. */
    SYSTEMONE,
    /** Any chat model (OpenAI-compatible, Anthropic, Ollama) prompted to answer as JSON. */
    LLM
  }
}
