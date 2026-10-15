// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.RunBudgets;
import com.infenia.yukta.plugin.util.SpelUtils;
import com.infenia.yukta.plugin.util.VariableResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.CouplingBetweenObjects",
  "PMD.OnlyOneReturn",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
@Slf4j
@Component
public final class LlmCallPlugin implements ProcessorPlugin {

  /** Extra time the node gets beyond the attempts, in seconds. */
  private static final long MARGIN_SECONDS = 5L;

  /** Retries counted when sizing the node timeout, which keeps the backoff term bounded. */
  private static final long MAX_RETRIES_COUNTED = 10L;

  private static final String TYPE = "LLM_CALL";
  private static final String PROVIDER = "provider";
  private static final String MODEL = "model";
  private static final String TIMEOUT = "timeout";

  /** Calls in flight at once when the node does not set {@code concurrency}. */
  private static final int DEFAULT_CONCURRENCY = 16;

  private static final String RETRIES_KEY = "retries";
  private static final String HARD_STOP = "hardStop";
  private static final String FAILURE_MODE = "failureMode";
  private static final String ROUTE_BY_STATUS = "routeByStatus";
  private static final String PRICES = "prices";
  private static final String BUDGET = "budget";
  private static final String PORT_SUCCESS = "success";
  private static final String PORT_FAILURE = "failure";
  private static final String PORT_DEFAULT = "default";
  private static final String SUCCESS_KEY = "success";
  private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
  private static final double TOKENS_PER_PRICE_UNIT = 1_000_000.0;
  private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

  private final LlmGateway gateway;
  private final VariableResolver resolver;
  private final PluginMetricsReporter reporter;
  private final RunBudgets budgets;
  @Nullable private final MeterRegistry meters;

  /**
   * Creates the plugin without metrics.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the gateway is a shared Spring bean
  public LlmCallPlugin(final LlmGateway gateway, final VariableResolver resolver) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = PluginMetricsReporter.NOOP;
    this.budgets = new RunBudgets();
    this.meters = null;
  }

  /**
   * Spring constructor.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   * @param reporterProvider optional metrics reporter
   */
  public LlmCallPlugin(
      final LlmGateway gateway,
      final VariableResolver resolver,
      final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this(gateway, resolver, reporterProvider, null);
  }

  /**
   * Spring constructor with token and cost meters.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   * @param reporterProvider optional metrics reporter
   * @param meterProvider optional Micrometer registry for the token and cost meters
   */
  @Autowired
  @SuppressWarnings("PMD.LawOfDemeter")
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the gateway is a shared Spring bean
  public LlmCallPlugin(
      final LlmGateway gateway,
      final VariableResolver resolver,
      final ObjectProvider<PluginMetricsReporter> reporterProvider,
      @Nullable final ObjectProvider<MeterRegistry> meterProvider) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
    this.budgets = RunBudgets.shared();
    this.meters = meterProvider == null ? null : meterProvider.getIfAvailable();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Calls an LLM (OpenAI-compatible, Anthropic, Ollama) and emits {success, content, "
            + "actions, usage, latencyMs, model, cost?}. <agent_action> blocks are extracted. "
            + "On failure with failureMode CONTINUE the input payload passes through.",
        usagePattern(),
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            "provider",
            true,
            null,
            "LLM provider",
            "openai",
            "openai-compatible",
            "anthropic",
            "ollama"),
        ConfigField.required("model", ConfigField.Type.STRING, "Model name"),
        ConfigField.optional("prompt", ConfigField.Type.EXPRESSION, null, "Last user turn"),
        ConfigField.optional(
            "messages",
            ConfigField.Type.LIST,
            null,
            "Chat turns [{role, content}] or an expression"),
        ConfigField.optional("system", ConfigField.Type.EXPRESSION, null, "System prompt"),
        ConfigField.optional("baseUrl", ConfigField.Type.STRING, null, "Endpoint override"),
        ConfigField.optional("apiKey", ConfigField.Type.SECRET, null, "API key (decrypted:<key>)"),
        ConfigField.optional("temperature", ConfigField.Type.NUMBER, null, "Sampling temperature"),
        ConfigField.optional("maxTokens", ConfigField.Type.NUMBER, null, "Max output tokens"),
        ConfigField.optional("timeout", ConfigField.Type.NUMBER, 120, "Timeout in seconds"),
        ConfigField.optional("retries", ConfigField.Type.NUMBER, 0, "Retries with backoff"),
        ConfigField.optional(
            "tools",
            ConfigField.Type.LIST,
            null,
            "Tools the model may call: [{name, description, inputSchema}] or an expression, e.g."
                + " the tools of an MCP_LIST_TOOLS node; calls come back in actions"),
        ConfigField.optional(
            "budget",
            ConfigField.Type.MAP,
            null,
            "Limits for all calls of one run: {maxTokens, maxCost, maxCalls, agent, hardStop}; cost"
                + " needs prices; the budget is shared with MCP_TOOL_CALL, and an agent is counted"
                + " apart"),
        ConfigField.choice(
            "failureMode", false, "ERROR", "What to do on a failed call", "ERROR", "CONTINUE"),
        ConfigField.optional(
            "routeByStatus",
            ConfigField.Type.BOOLEAN,
            false,
            "Emit on success/failure ports (needs failureMode CONTINUE)"),
        ConfigField.optional(
            ConfigValidation.CONCURRENCY_KEY,
            ConfigField.Type.NUMBER,
            DEFAULT_CONCURRENCY,
            "Model calls in flight at once"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  private static String usagePattern() {
    // CHECKSTYLE.OFF: Indentation
    // CHECKSTYLE.OFF: TextBlockGoogleStyleFormatting
    return """
    Configuration Parameters (all values support variable expressions):

    REQUIRED:
    - provider: openai | openai-compatible | anthropic | ollama
    - model: String, e.g. "gpt-4o-mini", "claude-sonnet-5-5", "llama3"
    - messages: List of {role: user|assistant, content}, given as a literal list or as a SpEL
      expression over the message, e.g. "payload['history']" ("${...}" cannot be used here: it
      turns lists into text). An assistant turn may carry the "actions" of its answer, and
      {role: tool, toolCallId, toolName, content} turns give the result of each call, so the model
      sees a proper tool exchange instead of a user message
      and/or prompt: String, appended as a final user turn (e.g. "${payload.message}")

    OPTIONAL:
    - baseUrl: API base URL (default: provider default; set it for Groq, vLLM, NIM, Ollama)
    - apiKey: String, use decrypted:<key> to read it from the secret store
    - system: system prompt String
    - temperature: Double, maxTokens: Integer
    - timeout: seconds per attempt, positive (default: 120)
    - concurrency: model calls in flight at once, positive (default: 16)
    - retries: extra attempts with exponential backoff (default: 0)
    - prices: {input, output} price per 1M tokens; adds "cost" to the output
    - tools: [{name, description, inputSchema}] or an expression (e.g. payload.tools from an
      MCP_LIST_TOOLS node). The model may answer with calls to them; they are returned in
      "actions" as {tool, arguments, id}, like <agent_action> blocks, and are not run here: wire
      the actions to an MCP_TOOL_CALL node and feed its result back as the next user turn.
    - budget: {maxTokens, maxCost} for all calls of one run (maxCost needs prices); once used up
      the next call fails like a failed call (failureMode applies). One call may overshoot.
      With hardStop: true the next call ends the whole run instead: it is not retried, dropped
      or routed by the node's engine policy, and failureMode does not absorb it.
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
    return PluginGuard.counted(
        input.flatMapSequential(
            message -> call(message, config),
            ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY)),
        reporter,
        "llm");
  }

  private Flux<Message<?>> call(final Message<?> message, final Map<String, Object> config) {
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    return Flux.deferContextual(
        ctx -> {
          final String runId = String.valueOf(ctx.getOrDefault("executionId", "unknown"));
          final String nodeId = String.valueOf(ctx.getOrDefault("nodeId", "unknown"));
          return resolveRequest(message, config)
              .flatMapMany(
                  request -> {
                    final long start = System.nanoTime();
                    return budgeted(runId, nodeId, config, attempt(request, retries(config)))
                        .<Message<?>>map(
                            response -> {
                              final Map<String, Object> payload =
                                  success(config, response, elapsedMillis(start));
                              budgets.add(
                                  RunBudgets.key(runId, budgetOf(config)),
                                  response.inputTokens() + response.outputTokens(),
                                  payload.get("cost") instanceof final Double cost ? cost : 0.0);
                              count(request, response, payload);
                              return output(message, config, payload, null);
                            })
                        .onErrorResume(
                            error ->
                                RunAbortedException.isAbort(error)
                                    ? Mono.<Message<?>>error(error)
                                    : failureMode == FailureMode.CONTINUE
                                        ? Mono.<Message<?>>just(
                                            output(
                                                message,
                                                config,
                                                message.payload(),
                                                failureText(error)))
                                        : Mono.error(
                                            new WorkflowExecutionException(
                                                "LLM call failed: " + error.getMessage(), error)))
                        .flux();
                  });
        });
  }

  /**
   * Counts the tokens and the cost of a call per provider ({@code llm.tokens}, {@code llm.cost}).
   */
  private void count(
      final LlmRequest request, final LlmResponse response, final Map<String, Object> payload) {
    if (meters != null) {
      final String provider = request.provider().name();
      meters
          .counter("llm.tokens", PROVIDER, provider, "direction", "input")
          .increment(response.inputTokens());
      meters
          .counter("llm.tokens", PROVIDER, provider, "direction", "output")
          .increment(response.outputTokens());
      if (payload.get("cost") instanceof final Double cost) {
        meters.counter("llm.cost", PROVIDER, provider).increment(cost);
      }
    }
  }

  private static Map<?, ?> budgetOf(final Map<String, Object> config) {
    return config.get(BUDGET) instanceof final Map<?, ?> budget ? budget : null;
  }

  /** Refuses the call if the run has used up its {@code budget}. */
  private Mono<LlmResponse> budgeted(
      final String runId,
      final String nodeId,
      final Map<String, Object> config,
      final Mono<LlmResponse> call) {
    return config.get(BUDGET) instanceof final Map<?, ?> budget
        ? budgets
            .exceeded(RunBudgets.key(runId, budget), budget)
            .<Mono<LlmResponse>>map(
                why -> {
                  reporter.increment(nodeId, "llm.budget.exceeded", "llm");
                  return Mono.error(
                      // hardStop ends the whole run; otherwise it is a failed call like any
                      // other and failureMode / routeByStatus decide what follows
                      Boolean.TRUE.equals(budget.get(HARD_STOP))
                          ? new RunAbortedException("LLM budget exceeded: " + why)
                          : new IllegalStateException("LLM budget exceeded: " + why));
                })
            .orElse(call)
        : call;
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
    final List<Object> actions = new ArrayList<>(parsed.actions());
    response.toolCalls().forEach(call -> actions.add(AgentActions.fromToolCall(call)));
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(SUCCESS_KEY, true);
    payload.put("content", parsed.content());
    payload.put("actions", actions);
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
                resolve(config.get("maxTokens"), message),
                Mono.fromSupplier(() -> messages(config.get("tools"), message))),
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
                  turns(t.getT5().getT2(), t.getT5().getT3()),
                  ToolSpecs.parse(t.getT5().getT6()));
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
      list.stream()
          .filter(Map.class::isInstance)
          .map(item -> turn((Map<?, ?>) item))
          .forEach(turns::add);
    }
    if (text(prompt) != null) {
      turns.add(new ChatTurn("user", prompt.toString()));
    }
    if (turns.isEmpty()) {
      throw new IllegalArgumentException("messages and/or prompt must yield at least one turn");
    }
    return turns;
  }

  private static ChatTurn turn(final Map<?, ?> turn) {
    return new ChatTurn(
        String.valueOf(turn.get("role")),
        String.valueOf(turn.get("content")),
        Objects.toString(turn.get("toolCallId"), null),
        Objects.toString(turn.get("toolName"), null),
        AgentActions.toToolCalls(turn.get("actions")));
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
    RunBudgets.validate(config);
    ToolSpecs.validate(config.get("tools"));
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    if (routeByStatus(config) && failureMode != FailureMode.CONTINUE) {
      throw new IllegalArgumentException("routeByStatus requires failureMode: CONTINUE");
    }
  }

  private static void checkNumbers(final Map<String, Object> config) {
    ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY);
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
