// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import com.infenia.yukta.plugin.decision.ModelSpec;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about the model-based control plane that can be set from configuration, prompt
 * included. Bound from {@code yukta.control-plane.model.*}; the rules-first plugin binds the same
 * shape below its own prefix.
 *
 * <pre>{@code
 * yukta.control-plane.model:
 *   name: tev1:0.8b                 # enables the plugin
 *   backend: SYSTEMONE              # or LLM, with provider: openai | anthropic | ollama
 *   instructions: What should the person supervising this workflow do about this event?
 *   options:
 *     continue: {description: Normal operation...,  command: NONE}
 *     pause:    {description: Repeated failures..., command: PAUSE}
 *   events: [NodeStatusChanged, WorkflowStatusChanged]
 * }</pre>
 *
 * @param backend how the model is called; System One by default
 * @param provider for {@link ModelSpec.Backend#LLM}: {@code openai}, {@code anthropic} or {@code
 *     ollama}
 * @param name the model name; required
 * @param baseUrl the endpoint; a local Ollama for System One by default
 * @param apiKey the API key, if the endpoint needs one
 * @param timeout how long to wait for an answer
 * @param minProbability a choice acts only when the model rates it at least this likely
 * @param instructions the question put to the model
 * @param options the answers the model may give, by key, each with a description of when it applies
 *     and the command it triggers; at least two
 * @param keepAlive for System One: how long Ollama keeps the model loaded, such as {@code 30m} or
 *     {@code -1}; unset leaves it to Ollama
 * @param numCtx for System One: the context size to load the model with; unset leaves it to Ollama
 * @param events the kinds of event to ask about: {@code NodeStatusChanged}, {@code
 *     WorkflowStatusChanged}, {@code ControlOutcome}, {@code LogLine}
 * @param maxCallsPerMinute most model calls per minute; events beyond it get no opinion (counted as
 *     {@code skipped}) instead of waiting for the model; 0 asks about every matching event
 */
@ConfigurationProperties("yukta.control-plane.model")
@SuppressWarnings({"PMD.NullAssignment", "PMD.UseConcurrentHashMap"})
public record ModelPlaneSettings(
    ModelSpec.Backend backend,
    @Nullable String provider,
    String name,
    @Nullable String baseUrl,
    @Nullable String apiKey,
    @DefaultValue("2s") Duration timeout,
    @DefaultValue("0.6") double minProbability,
    String instructions,
    Map<String, Option> options,
    Set<String> events,
    @Nullable String keepAlive,
    @Nullable Integer numCtx,
    @DefaultValue("0") int maxCallsPerMinute) {

  /** A choice needs at least two options. */
  private static final int MIN_OPTIONS = 2;

  /** Where a local Ollama listens. */
  private static final String LOCAL_OLLAMA = "http://localhost:11434";

  /** The question asked when none is configured. */
  public static final String DEFAULT_INSTRUCTIONS =
      "What should the person supervising this workflow do about this event?";

  /** What an answer does. */
  public enum Command {
    /** Nothing: leave the execution alone. */
    NONE,
    /** Pause the workflow. */
    PAUSE,
    /** Drain and stop the workflow. */
    STOP
  }

  /**
   * One answer the model may give.
   *
   * @param description when it applies; a small model needs this to be explicit
   * @param command what it triggers
   */
  public record Option(String description, Command command) {}

  /**
   * Fills in defaults and checks the settings.
   *
   * @throws IllegalArgumentException if the name is missing, fewer than two options are given, or
   *     the chat backend has no provider
   */
  public ModelPlaneSettings {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("The model name is required");
    }
    backend = backend == null ? ModelSpec.Backend.SYSTEMONE : backend;
    if (backend == ModelSpec.Backend.LLM && (provider == null || provider.isBlank())) {
      throw new IllegalArgumentException("A provider is required for backend LLM");
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = backend == ModelSpec.Backend.SYSTEMONE ? LOCAL_OLLAMA : null;
    }
    instructions =
        instructions == null || instructions.isBlank() ? DEFAULT_INSTRUCTIONS : instructions;
    options =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(options == null || options.isEmpty() ? defaultOptions() : options));
    if (options.size() < MIN_OPTIONS) {
      throw new IllegalArgumentException("At least two options are needed");
    }
    if (maxCallsPerMinute < 0) {
      throw new IllegalArgumentException("max-calls-per-minute cannot be negative");
    }
    events =
        events == null || events.isEmpty()
            ? Set.of("NodeStatusChanged", "WorkflowStatusChanged")
            : Set.copyOf(events);
  }

  /** The prompt that scored 9 of 10 against tev1:0.8b: two options with explicit descriptions. */
  private static Map<String, Option> defaultOptions() {
    final Map<String, Option> options = new LinkedHashMap<>();
    options.put(
        "continue",
        new Option(
            "Normal operation, a success, a retry that may still succeed, or an execution that"
                + " already finished. Nothing to do.",
            Command.NONE));
    options.put(
        "pause",
        new Option(
            "Repeated failures, exhausted retries, authentication errors or resource exhaustion"
                + " that will not fix themselves. A person must look.",
            Command.PAUSE));
    return options;
  }
}
