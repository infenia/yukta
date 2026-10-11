// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SpelUtils;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the plugins that let a decision model decide or route. A subclass says what to ask
 * ({@link #decide}) and what to do with the answer; this class resolves the model, the state and
 * the output message.
 *
 * <p>A model that cannot answer fails the message with a {@link WorkflowExecutionException}; use
 * the node's {@code engine} policy ({@code retry}, {@code onError}) to retry or route failures.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
public abstract class DecisionPlugin implements ProcessorPlugin {

  /** The port of a plugin that does not route. */
  protected static final String PORT_DEFAULT = "default";

  /** Config key: where the model result goes in the message metadata. */
  private static final String INTO = "into";

  /** Config key: expression for what the model is asked about. */
  private static final String STATE = "state";

  /** Config key: the timeout in seconds. */
  private static final String TIMEOUT = "timeout";

  /** Where a local Ollama listens. */
  private static final String LOCAL_OLLAMA = "http://localhost:11434";

  /** Seconds to wait for the model by default. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 30;

  /** State is cut to this many characters; System One refuses requests above 64 KiB. */
  private static final int MAX_STATE = 15_000;

  /** Shared JSON mapper. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Asks the model. */
  private final DecisionModel model;

  /** Resolves variable expressions and secrets in the config. */
  private final VariableResolver resolver;

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param resolver resolves variable expressions and secrets in the config
   */
  protected DecisionPlugin(final DecisionModel model, final VariableResolver resolver) {
    this.model = model;
    this.resolver = resolver;
  }

  /**
   * Decides what to do with one message.
   *
   * @param message the message
   * @param config the node configuration
   * @return the message to emit
   */
  protected abstract Mono<Message<?>> decide(Message<?> message, Map<String, Object> config);

  /**
   * Checks the configuration the plugin adds to the common fields.
   *
   * @param config the node configuration
   * @throws IllegalArgumentException if it is invalid
   */
  protected abstract void check(Map<String, Object> config);

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.flatMapSequential(message -> decide(message, config));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          require(config, "model");
          if (backend(config) == ModelSpec.Backend.LLM) {
            require(config, "provider");
          }
          check(config);
        });
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    return seconds(config).plusSeconds(5);
  }

  /**
   * The fields every decision plugin has.
   *
   * @param into the default metadata key of the result
   * @return the fields
   */
  protected static List<ConfigField> commonFields(final String into) {
    return List.of(
        ConfigField.choice(
            "backend",
            false,
            "SYSTEMONE",
            "SYSTEMONE: Ollama System One (calibrated probabilities); LLM: any chat model",
            "SYSTEMONE",
            "LLM"),
        ConfigField.choice(
            "provider",
            false,
            null,
            "Chat provider, for backend LLM",
            "openai",
            "anthropic",
            "ollama"),
        ConfigField.required("model", ConfigField.Type.STRING, "Model name"),
        ConfigField.optional("baseUrl", ConfigField.Type.STRING, null, "Endpoint override"),
        ConfigField.optional("apiKey", ConfigField.Type.SECRET, null, "API key (decrypted:<key>)"),
        ConfigField.optional(
            TIMEOUT, ConfigField.Type.NUMBER, DEFAULT_TIMEOUT_SECONDS, "Timeout in seconds"),
        ConfigField.optional(
            STATE, ConfigField.Type.EXPRESSION, "payload", "Expression for what the model sees"),
        ConfigField.optional(INTO, ConfigField.Type.STRING, into, "Metadata key for the result"));
  }

  /**
   * Asks the model.
   *
   * @param message the message the question is about
   * @param config the node configuration
   * @param state what the model sees
   * @param questions the questions
   * @return the answers by question name
   */
  protected final Mono<Map<String, Answer>> ask(
      final Message<?> message,
      final Map<String, Object> config,
      final String state,
      final Map<String, Question> questions) {
    return Mono.zip(
            resolve(config.get("model"), message),
            resolve(config.get("baseUrl"), message),
            resolve(config.get("apiKey"), message))
        .flatMap(
            t ->
                model.answer(
                    new ModelSpec(
                        backend(config),
                        text(config.get("provider")),
                        t.getT1(),
                        baseUrl(config, t.getT2()),
                        blankToNull(t.getT3()),
                        seconds(config)),
                    state,
                    questions))
        .onErrorMap(
            error ->
                error instanceof WorkflowExecutionException
                    ? error
                    : new WorkflowExecutionException(
                        "Decision model failed: " + error.getMessage(), error));
  }

  /**
   * What the model is asked about: the {@code state} expression over the message, default the
   * payload.
   *
   * @param message the message
   * @param config the node configuration
   * @return the text
   */
  protected static String state(final Message<?> message, final Map<String, Object> config) {
    final Object expression = config.get(STATE);
    final Object value =
        expression == null || expression.toString().isBlank()
            ? message.payload()
            : SpelUtils.evaluateSync(expression.toString(), message);
    final String text =
        value instanceof final String string ? string : JSON.writeValueAsString(value);
    return text.length() > MAX_STATE ? text.substring(0, MAX_STATE) : text;
  }

  /**
   * The message to emit: the input with the model result added to its metadata.
   *
   * @param message the input
   * @param config the node configuration
   * @param into the metadata key to use when the config names none
   * @param result what the model decided
   * @param port the output port
   * @return the output message
   */
  protected static Message<?> emit(
      final Message<?> message,
      final Map<String, Object> config,
      final String into,
      final Object result,
      final String port) {
    final Map<String, Object> metadata = new HashMap<>(message.metadata());
    metadata.put(text(config.getOrDefault(INTO, into)), result);
    // from() inherits the upstream port, so always stamp one explicitly
    return DefaultMessage.from(message, message.payload())
        .withMetadata(metadata)
        .withSourcePort(port);
  }

  /**
   * Picks an answer of an expected kind.
   *
   * @param answers the model's answers
   * @param name the question
   * @param type the expected kind
   * @param <T> the kind
   * @return the answer
   */
  protected static <T extends Answer> T answer(
      final Map<String, Answer> answers, final String name, final Class<T> type) {
    final Answer answer = answers.get(name);
    if (!type.isInstance(answer)) {
      throw new WorkflowExecutionException("The model gave no usable answer to '" + name + "'");
    }
    return type.cast(answer);
  }

  /**
   * Reads a string option.
   *
   * @param config the node configuration
   * @param key the key
   * @param fallback the value when absent
   * @return the value
   */
  protected static String string(
      final Map<String, Object> config, final String key, final String fallback) {
    final Object value = config.get(key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  /**
   * Reads a number option.
   *
   * @param config the node configuration
   * @param key the key
   * @param fallback the value when absent
   * @return the value
   */
  protected static double number(
      final Map<String, Object> config, final String key, final double fallback) {
    final Object value = config.get(key);
    return value == null ? fallback : MapUtils.convert(value, Double.class);
  }

  /**
   * Reads a map option.
   *
   * @param config the node configuration
   * @param key the key
   * @return the map
   * @throws IllegalArgumentException if it is absent or not a map
   */
  protected static Map<String, Object> map(final Map<String, Object> config, final String key) {
    if (config.get(key) instanceof final Map<?, ?> map && !map.isEmpty()) {
      final Map<String, Object> copy = new java.util.LinkedHashMap<>();
      map.forEach((k, v) -> copy.put(String.valueOf(k), v));
      return copy;
    }
    throw new IllegalArgumentException(key + " must be a non-empty map");
  }

  /**
   * Requires a non-blank option.
   *
   * @param config the node configuration
   * @param key the key
   * @throws IllegalArgumentException if it is missing
   */
  protected static void require(final Map<String, Object> config, final String key) {
    if (config.get(key) == null || config.get(key).toString().isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
  }

  /** System One has no cloud endpoint; the default is a local Ollama. */
  private static String baseUrl(final Map<String, Object> config, final String configured) {
    final String url = blankToNull(configured);
    return url == null && backend(config) == ModelSpec.Backend.SYSTEMONE ? LOCAL_OLLAMA : url;
  }

  private static ModelSpec.Backend backend(final Map<String, Object> config) {
    return ModelSpec.Backend.valueOf(
        string(config, "backend", "SYSTEMONE").trim().toUpperCase(Locale.ROOT));
  }

  private static Duration seconds(final Map<String, Object> config) {
    return Duration.ofSeconds(
        MapUtils.convert(config.getOrDefault(TIMEOUT, DEFAULT_TIMEOUT_SECONDS), Long.class));
  }

  private static String text(final Object value) {
    return value == null ? null : value.toString();
  }

  private static String blankToNull(final String value) {
    return value.isBlank() ? null : value;
  }

  private Mono<String> resolve(final Object value, final Message<?> message) {
    return value == null
        ? Mono.just("")
        : resolver.resolve(value, message).map(Object::toString).defaultIfEmpty("");
  }
}
