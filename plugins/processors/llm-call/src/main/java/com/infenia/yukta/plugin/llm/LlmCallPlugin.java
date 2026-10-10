// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SpelUtils;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Processor plugin calling an LLM (OpenAI-compatible, Anthropic or Ollama) per input message.
 *
 * <p>Config values support variable expressions, so prompts and history come from the payload, e.g.
 * {@code "messages": "${payload.history}"}. Failover is a workflow pattern: enable {@code
 * routeByStatus} and wire the {@code failure} port to a second llm-call node.
 */
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.GodClass",
  "PMD.OnlyOneReturn",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallPlugin implements ProcessorPlugin {

  /** Extra time the node gets beyond the attempts, in seconds. */
  private static final long MARGIN_SECONDS = 5L;

  /** Retries counted when sizing the node timeout, which keeps the backoff term bounded. */
  private static final long MAX_RETRIES_COUNTED = 10L;

  private static final String TYPE = "LLM_CALL";
  private static final String PROVIDER = "provider";
  private static final String MODEL = "model";
  private static final String TIMEOUT = "timeout";
  private static final String RETRIES_KEY = "retries";
  private static final String FAILURE_MODE = "failureMode";
  private static final String ROUTE_BY_STATUS = "routeByStatus";
  private static final String PRICES = "prices";
  private static final String PORT_SUCCESS = "success";
  private static final String PORT_FAILURE = "failure";
  private static final String PORT_DEFAULT = "default";
  private static final String SUCCESS_KEY = "success";
  private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
  private static final double TOKENS_PER_PRICE_UNIT = 1_000_000.0;
  private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

  private final LlmGateway gateway;
  private final VariableResolver resolver;

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Calls an LLM (OpenAI-compatible, Anthropic, Ollama) and emits {success, content, "
        + "actions, usage, latencyMs, model, cost?}. <agent_action> blocks are extracted. "
        + "On failure with failureMode CONTINUE the input payload passes through.";
  }

  @Override
  public String getUsagePattern() {
    // CHECKSTYLE.OFF: Indentation
    // CHECKSTYLE.OFF: TextBlockGoogleStyleFormatting
    return """
    Configuration Parameters (all values support variable expressions):

    REQUIRED:
    - provider: openai | openai-compatible | anthropic | ollama
    - model: String, e.g. "gpt-4o-mini", "claude-sonnet-5-5", "llama3"
    - messages: List of {role: user|assistant, content}, given as a literal list or as a SpEL
      expression over the message, e.g. "payload['history']" ("${...}" cannot be used here: it
      turns lists into text)
      and/or prompt: String, appended as a final user turn (e.g. "${payload.message}")

    OPTIONAL:
    - baseUrl: API base URL (default: provider default; set it for Groq, vLLM, NIM, Ollama)
    - apiKey: String, use decrypted:<key> to read it from the secret store
    - system: system prompt String
    - temperature: Double, maxTokens: Integer
    - timeout: seconds per attempt, positive (default: 120)
    - retries: extra attempts with exponential backoff (default: 0)
    - prices: {input, output} price per 1M tokens; adds "cost" to the output
    - failureMode: ERROR | CONTINUE (default: ERROR)
      ERROR: a failed call fails the node
      CONTINUE: emit the ORIGINAL payload unchanged with metadata llmSuccess=false and
                llmError, so a fallback llm-call can reuse the identical config
    - routeByStatus: Boolean (default: false, requires failureMode: CONTINUE)
      Stamps messages with source port "success" or "failure". Wire the failure port to a
      second llm-call node for model failover.

    OUTPUT:
    - payload = {success, content, actions, usage{inputTokens, outputTokens}, latencyMs,
                 model, cost?}; metadata llmSuccess=true
    - on failure (CONTINUE): the input payload passes through; metadata llmSuccess=false and
      llmError hold the failure
    """;
  }

  /**
   * The node timeout covers every attempt: each may take the call timeout, with the retry backoff
   * between them.
   *
   * @param config the node configuration
   * @return the time all attempts may take, plus a margin
   */
  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    final long seconds =
        config.get(TIMEOUT) instanceof final Number number && number.longValue() > 0
            ? number.longValue()
            : DEFAULT_TIMEOUT_SECONDS;
    final long retries =
        config.get(RETRIES_KEY) instanceof final Number number && number.longValue() > 0
            ? Math.min(number.longValue(), MAX_RETRIES_COUNTED)
            : 0;
    return Duration.ofSeconds((seconds + 1) * (retries + 1) + MARGIN_SECONDS)
        .plus(RETRY_BACKOFF.multipliedBy(1L << retries));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    if (routeByStatus(config)) {
      return List.of(PORT_SUCCESS, PORT_FAILURE);
    }
    return getOutputPorts();
  }

  @Override
  public boolean isBlocking() {
    return true;
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.flatMapSequential(message -> call(message, config));
  }

  private Flux<Message<?>> call(final Message<?> message, final Map<String, Object> config) {
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    return resolveRequest(message, config)
        .flatMapMany(
            request -> {
              final long start = System.nanoTime();
              return attempt(request, retries(config))
                  .<Message<?>>map(
                      response ->
                          output(
                              message,
                              config,
                              success(config, response, elapsedMillis(start)),
                              null))
                  .onErrorResume(
                      error ->
                          failureMode == FailureMode.CONTINUE
                              ? Mono.<Message<?>>just(
                                  output(message, config, message.payload(), failureText(error)))
                              : Mono.error(
                                  new WorkflowExecutionException(
                                      "LLM call failed: " + error.getMessage(), error)))
                  .flux();
            });
  }

  private Mono<LlmResponse> attempt(final LlmRequest request, final long retries) {
    return Mono.fromCallable(() -> gateway.call(request))
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(request.timeout().plusSeconds(1))
        .retryWhen(
            Retry.backoff(retries, RETRY_BACKOFF)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private Map<String, Object> success(
      final Map<String, Object> config, final LlmResponse response, final long latencyMs) {
    final AgentActions.Parsed parsed = AgentActions.parse(response.text());
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(SUCCESS_KEY, true);
    payload.put("content", parsed.content());
    payload.put("actions", parsed.actions());
    payload.put(
        "usage",
        Map.of("inputTokens", response.inputTokens(), "outputTokens", response.outputTokens()));
    payload.put("latencyMs", latencyMs);
    payload.put(MODEL, response.model());
    if (config.get(PRICES) instanceof final Map<?, ?> prices) {
      payload.put("cost", cost(prices, response));
    }
    return payload;
  }

  private static double cost(final Map<?, ?> prices, final LlmResponse response) {
    return (response.inputTokens() * price(prices.get("input"))
            + response.outputTokens() * price(prices.get("output")))
        / TOKENS_PER_PRICE_UNIT;
  }

  private static double price(final Object value) {
    return value == null ? 0.0 : MapUtils.convert(value, Double.class);
  }

  private static String failureText(final Throwable error) {
    return String.valueOf(error.getMessage());
  }

  /** Builds the output message; a non-null {@code error} marks a failed call. */
  private static Message<?> output(
      final Message<?> original,
      final Map<String, Object> config,
      final Object payload,
      final String error) {
    final boolean success = error == null;
    final Map<String, Object> metadata = new HashMap<>(original.metadata());
    metadata.put("llmSuccess", success);
    if (!success) {
      metadata.put("llmError", error);
    }
    final Message<?> output = DefaultMessage.from(original, payload).withMetadata(metadata);
    // from() inherits the upstream port, so always stamp one explicitly
    final String port =
        routeByStatus(config) ? (success ? PORT_SUCCESS : PORT_FAILURE) : PORT_DEFAULT;
    return output.withSourcePort(port);
  }

  private Mono<LlmRequest> resolveRequest(
      final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(
            resolve(config.get(PROVIDER), message),
            resolve(config.get(MODEL), message),
            resolve(config.get("baseUrl"), message),
            resolve(config.get("apiKey"), message),
            Mono.zip(
                resolve(config.get("system"), message),
                Mono.fromSupplier(() -> messages(config.get("messages"), message)),
                resolve(config.get("prompt"), message),
                resolve(config.get("temperature"), message),
                resolve(config.get("maxTokens"), message)),
            resolve(config.getOrDefault(TIMEOUT, DEFAULT_TIMEOUT_SECONDS), message))
        .map(
            t -> {
              final Long configured = number(t.getT6(), Long.class);
              final long timeout = configured == null ? DEFAULT_TIMEOUT_SECONDS : configured;
              if (timeout <= 0) {
                throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
              }
              return new LlmRequest(
                  Provider.from(t.getT1()),
                  text(t.getT3()),
                  text(t.getT4()),
                  t.getT2().toString(),
                  number(t.getT5().getT4(), Double.class),
                  number(t.getT5().getT5(), Integer.class),
                  Duration.ofSeconds(timeout),
                  text(t.getT5().getT1()),
                  turns(t.getT5().getT2(), t.getT5().getT3()));
            });
  }

  /**
   * Evaluates the {@code messages} option. A string is a SpEL expression over the message (e.g.
   * {@code payload['history']}) because variable expressions stringify non-text values; any other
   * value, such as a literal list, is used as is.
   */
  private static Object messages(final Object configured, final Message<?> message) {
    final Object value =
        configured instanceof final String expression && !expression.isBlank()
            ? SpelUtils.evaluateSync(expression, message)
            : configured;
    return Objects.requireNonNullElse(value, "");
  }

  private static String text(final Object value) {
    return value.toString().isEmpty() ? null : value.toString();
  }

  private static <T> T number(final Object value, final Class<T> type) {
    return value.toString().isEmpty() ? null : MapUtils.convert(value, type);
  }

  private static List<ChatTurn> turns(final Object messages, final Object prompt) {
    final List<ChatTurn> turns = new ArrayList<>();
    if (messages instanceof final List<?> list) {
      for (final Object item : list) {
        if (item instanceof final Map<?, ?> turn) {
          turns.add(
              new ChatTurn(String.valueOf(turn.get("role")), String.valueOf(turn.get("content"))));
        }
      }
    }
    if (text(prompt) != null) {
      turns.add(new ChatTurn("user", prompt.toString()));
    }
    if (turns.isEmpty()) {
      throw new IllegalArgumentException("messages and/or prompt must yield at least one turn");
    }
    return turns;
  }

  private Mono<Object> resolve(final Object value, final Message<?> message) {
    return value == null ? Mono.just("") : resolver.resolve(value, message).defaultIfEmpty("");
  }

  private static long retries(final Map<String, Object> config) {
    return MapUtils.convert(config.getOrDefault(RETRIES_KEY, 0), Long.class);
  }

  private static boolean routeByStatus(final Map<String, Object> config) {
    return MapUtils.convert(config.getOrDefault(ROUTE_BY_STATUS, false), Boolean.class);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.<Void>fromRunnable(() -> checkConfig(config))
        .doOnError(
            e -> log.atWarn().log("LLM call configuration validation failed: {}", e.getMessage()));
  }

  private static void checkConfig(final Map<String, Object> config) {
    require(config, PROVIDER);
    require(config, MODEL);
    if (config.get("messages") == null && config.get("prompt") == null) {
      throw new IllegalArgumentException("messages or prompt is mandatory");
    }
    final String provider = config.get(PROVIDER).toString();
    if (!provider.contains("${")) {
      Provider.from(provider);
    }
    checkNumbers(config);
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    if (routeByStatus(config) && failureMode != FailureMode.CONTINUE) {
      throw new IllegalArgumentException("routeByStatus requires failureMode: CONTINUE");
    }
  }

  private static void checkNumbers(final Map<String, Object> config) {
    if (config.get(TIMEOUT) instanceof final Number number && number.longValue() <= 0) {
      throw new IllegalArgumentException("timeout must be positive, got: " + number);
    }
    if (retries(config) < 0) {
      throw new IllegalArgumentException("retries must not be negative");
    }
  }

  private static void require(final Map<String, Object> config, final String key) {
    if (config.get(key) == null) {
      throw new IllegalArgumentException(key + " is mandatory");
    }
  }
}
