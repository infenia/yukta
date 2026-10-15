// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.SignalWaits;
import com.infenia.yukta.plugin.util.SpelUtils;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Processor plugin calling a tool on a remote MCP server per input message.
 *
 * <p>This is the "act" step of an agent loop: an LLM node decides on a tool call, this node runs it
 * and the result flows back to the LLM. It also lets one agent call another that is exposed as an
 * MCP server. Config values support variable expressions and {@code decrypted:} secrets. Retry and
 * error routing come from the node's {@code engine} policy; the message id and attempt are sent in
 * the request {@code _meta} so tools can de-duplicate retried side effects.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.OnlyOneReturn",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap",
  "PMD.LawOfDemeter",
  "PMD.GodClass",
  "PMD.CouplingBetweenObjects",
  "PMD.CyclomaticComplexity"
})
public final class McpToolCallPlugin implements ProcessorPlugin {

  /** The approvals this plugin waits for. */
  private final SignalWaits waits = SignalWaits.shared();

  private static final String TYPE = "MCP_TOOL_CALL";
  private static final String URL = "url";
  private static final String TOOL = "tool";
  private static final String ARGUMENTS = "arguments";
  private static final String HEADERS = "headers";
  private static final String ALLOWED_TOOLS = "allowedTools";
  private static final String UNKNOWN = "unknown";
  private static final String AUTH_TOKEN = "authToken";
  private static final String TIMEOUT = "timeout";
  private static final String RESULT_KEY = "resultKey";
  private static final String FAILURE_MODE = "failureMode";
  private static final String ROUTE_BY_STATUS = "routeByStatus";
  private static final String PORT_SUCCESS = "success";
  private static final String PORT_FAILURE = "failure";
  private static final String PORT_DEFAULT = "default";
  private static final String PORT_ERROR = "error";
  private static final String ATTEMPT = "attempt";
  private static final String RESET = "reset";
  private static final long DEFAULT_TIMEOUT_SECONDS = 60L;
  private static final long MARGIN_SECONDS = 5L;
  private static final Runnable NO_OP = Thread::onSpinWait;

  private final McpClientGateway gateway;
  private final VariableResolver resolver;
  private final PluginMetricsReporter reporter;
  private final MeterRegistry meters;

  /**
   * Creates the plugin.
   *
   * @param gateway the MCP client
   * @param resolver resolves variable expressions and secrets
   * @param reporterProvider optional per-node counter sink
   * @param meterProvider optional Micrometer registry for call timers
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the gateway is a shared Spring bean
  public McpToolCallPlugin(
      final McpClientGateway gateway,
      final VariableResolver resolver,
      final ObjectProvider<PluginMetricsReporter> reporterProvider,
      final ObjectProvider<MeterRegistry> meterProvider) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = reporterProvider.getIfAvailable(() -> (nodeId, metric, tag) -> {});
    this.meters = meterProvider.getIfAvailable();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Calls a tool on a remote MCP server per message and emits the tool result. "
            + "Use it as the 'act' step of an AI agent loop, or to call another agent.",
        usagePattern(),
        Optional.of(
            new UiDesign(
                """
                <div style="display:flex;align-items:center;gap:8px;width:100%;height:100%;\
                padding:0 12px;border:2px solid #cbd5e1;border-radius:12px;background:#f8fafc">\
                <div style="font-size:10px;font-weight:700;color:#64748b;text-transform:uppercase">\
                MCP</div>\
                <div style="font-size:12px;font-weight:700;color:#334155;overflow:hidden;\
                text-overflow:ellipsis;white-space:nowrap">{{nodeId}}</div></div>
                """,
                160, 60)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(
            URL,
            ConfigField.Type.STRING,
            "MCP server endpoint (streamable HTTP); ${...} supported"),
        ConfigField.required(TOOL, ConfigField.Type.STRING, "Tool name; ${...} supported"),
        ConfigField.optional(
            ARGUMENTS,
            ConfigField.Type.EXPRESSION,
            null,
            "Tool arguments: a map of values, or a SpEL expression returning a map"
                + " (default: the payload)"),
        ConfigField.optional(HEADERS, ConfigField.Type.MAP, null, "Extra request headers"),
        ConfigField.optional(
            ToolBudget.BUDGET,
            ConfigField.Type.MAP,
            null,
            "Limits for the calls of one run: {maxCalls, agent, hardStop}; shared with LLM_CALL"),
        ConfigField.optional(
            ALLOWED_TOOLS,
            ConfigField.Type.LIST,
            null,
            "Tools this node may call; any other tool (even one chosen by ${...}) is refused"),
        ConfigField.optional(
            ToolApproval.REQUIRE,
            ConfigField.Type.LIST,
            null,
            "Tools (or *) that wait for a human signal before they are called"),
        ConfigField.optional(
            ToolApproval.SIGNAL, ConfigField.Type.STRING, "approve", "Name of the approval signal"),
        ConfigField.optional(
            ToolApproval.TIMEOUT, ConfigField.Type.NUMBER, 3600, "Longest wait for the approval"),
        ConfigField.optional(
            AUTH_TOKEN, ConfigField.Type.SECRET, null, "Bearer token (decrypted:<key>)"),
        ConfigField.optional(TIMEOUT, ConfigField.Type.NUMBER, 60, "Timeout in seconds"),
        ConfigField.optional(
            RESULT_KEY,
            ConfigField.Type.STRING,
            null,
            "Also copy the tool result into this metadata key"),
        ConfigField.choice(
            FAILURE_MODE, false, "ERROR", "What to do on a failed call", "ERROR", "CONTINUE"),
        ConfigField.optional(
            ROUTE_BY_STATUS,
            ConfigField.Type.BOOLEAN,
            false,
            "Emit on success/failure ports (needs failureMode CONTINUE)"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  private static String usagePattern() {
    // CHECKSTYLE.OFF: Indentation
    // CHECKSTYLE.OFF: TextBlockGoogleStyleFormatting
    return """
    Configuration Parameters:

    REQUIRED:
    - url: MCP server endpoint, streamable HTTP (variable expressions supported)
    - tool: tool name (variable expressions supported)

    OPTIONAL:
    - arguments: map of tool arguments (values support variable expressions), or a SpEL string
      evaluated against the message that returns a map, e.g. "payload.toolCall.args".
      Default: the payload if it is a map, else {"input": payload}
    - headers: Map<String, String> (variable expressions and decrypted:<key> supported)
    - budget: {maxCalls, agent, hardStop} limits the calls of one run, counted together with the
      LLM_CALL budget of the same run (and agent); hardStop ends the whole run
    - allowedTools: list of tool names this node may call; any other tool is refused (use it when
      the tool is chosen by a model).
    - requireApproval: list of tool names (or "*") that wait for a human signal before they are
      called. Send it with signal_node (name = approvalSignal, default "approve"; decision
      "reject" refuses the call, anything else approves). approvalTimeoutSeconds: longest wait,
      default 3600; no answer refuses the call.
    - authToken: bearer token, e.g. "decrypted:github-mcp"
    - timeout: seconds, positive (default: 60)
    - resultKey: also copy the tool text result into this metadata key
    - failureMode: ERROR | CONTINUE (default: ERROR)
      ERROR: tool error or transport error fails the node (engine retry/onError applies)
      CONTINUE: the result is emitted with success=false so downstream can route
    - routeByStatus: Boolean (default: false, requires failureMode: CONTINUE)
      Stamps messages with source port "success" or "failure"

    OUTPUT:
    - payload = {success, tool, text, content, structured, error}
    - metadata: mcpSuccess, mcpTool, mcpLatencyMs
    - Control bus: send payload "reset" to drop cached MCP sessions.

    AGENT LOOP: LLM_CALL -> BRANCH (tool requested?) -> MCP_TOOL_CALL -> LLM_CALL.
    """;
  }

  /**
   * The node timeout follows the call timeout, plus a margin. Templated values resolve to strings,
   * so numeric strings count.
   *
   * @param config the node configuration
   * @return the configured (or default) timeout plus a margin
   */
  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    final long seconds = timeoutSeconds(config.get(TIMEOUT)).orElse(DEFAULT_TIMEOUT_SECONDS);
    return Duration.ofSeconds(seconds + MARGIN_SECONDS).plus(ToolApproval.wait(config));
  }

  private static Optional<Long> timeoutSeconds(final Object value) {
    final Optional<Long> parsed;
    if (value instanceof final Number number) {
      parsed = Optional.of(number.longValue());
    } else if (value instanceof final String text && text.trim().matches("\\d+")) {
      parsed = Optional.of(Long.parseLong(text.trim()));
    } else {
      parsed = Optional.empty();
    }
    return parsed.filter(seconds -> seconds > 0);
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return routeByStatus(config) ? List.of(PORT_SUCCESS, PORT_FAILURE) : getOutputPorts();
  }

  @Override
  public Mono<Void> prepare(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          if (config.get(ARGUMENTS) instanceof final String expression) {
            SpelUtils.preParse(expression);
          }
        });
  }

  @Override
  public Mono<Void> validateInContext(
      final WorkflowContext context, final Map<String, Object> config) {
    if (!routeByStatus(config)) {
      return Mono.empty();
    }
    final List<String> allowed = List.of(PORT_SUCCESS, PORT_FAILURE, PORT_ERROR);
    return context.outgoingEdges().stream()
        .map(WorkflowContext.WorkflowEdge::sourcePort)
        .filter(port -> port != null && !port.isBlank() && !allowed.contains(port))
        .findFirst()
        .<Mono<Void>>map(
            port ->
                Mono.error(
                    new IllegalArgumentException(
                        "MCP_TOOL_CALL with routeByStatus only emits on success/failure, not "
                            + port)))
        .orElseGet(Mono::empty);
  }

  @Override
  public Mono<Message<?>> onControlSignal(final Message<?> signal) {
    if (RESET.equals(String.valueOf(signal.payload()))) {
      gateway.reset();
      return Mono.empty();
    }
    return waits.deliver(signal);
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.flatMapSequential(message -> call(message, config));
  }

  private Flux<Message<?>> call(final Message<?> message, final Map<String, Object> config) {
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    final long started = System.nanoTime();
    return Mono.deferContextual(
            ctx -> {
              final String nodeId = ctx.getOrDefault("nodeId", UNKNOWN);
              final Runnable heartbeat =
                  ctx.hasKey(WorkflowContext.HEARTBEAT_KEY)
                      ? ctx.get(WorkflowContext.HEARTBEAT_KEY)
                      : NO_OP;
              return resolveSpec(message, config)
                  .flatMap(
                      spec ->
                          allowed(config, spec.tool())
                              .then(approved(ctx, config, spec.tool()))
                              .then(
                                  Mono.defer(
                                      () ->
                                          ToolBudget.charge(
                                              ctx.getOrDefault("executionId", UNKNOWN),
                                              nodeId,
                                              config,
                                              reporter)))
                              .then(Mono.defer(() -> gateway.call(spec, heartbeat)))
                              .onErrorResume(
                                  error ->
                                      failureMode == FailureMode.CONTINUE
                                              && !ToolBudget.isAbort(error)
                                          ? Mono.just(
                                              McpCallResult.transportFailure(error.toString()))
                                          : Mono.error(error))
                              .map(result -> new Outcome(nodeId, spec.tool(), result)));
            })
        .flatMapMany(outcome -> toMessage(message, config, failureMode, outcome, started));
  }

  /** Holds a tool that needs a human approval until the signal comes. */
  private Mono<Void> approved(
      final reactor.util.context.ContextView ctx,
      final Map<String, Object> config,
      final String tool) {
    return ToolApproval.required(config, tool)
        ? ToolApproval.await(
            waits,
            config,
            ctx.getOrDefault("executionId", UNKNOWN),
            ctx.getOrDefault("nodeId", UNKNOWN),
            tool)
        : Mono.empty();
  }

  /**
   * Refuses a tool that is not in {@code allowedTools}. The tool name may come from the message
   * (for example chosen by a model), so this is the node's guardrail. Not retried: it is a policy.
   */
  private static Mono<Void> allowed(final Map<String, Object> config, final String tool) {
    return config.get(ALLOWED_TOOLS) instanceof final List<?> tools
            && !tools.isEmpty()
            && !tools.contains(tool)
        ? Mono.error(
            new IllegalArgumentException("MCP tool '" + tool + "' is not in allowedTools " + tools))
        : Mono.empty();
  }

  /** A finished call, with the node that made it. */
  private record Outcome(String nodeId, String tool, McpCallResult result) {}

  private Flux<Message<?>> toMessage(
      final Message<?> original,
      final Map<String, Object> config,
      final FailureMode failureMode,
      final Outcome outcome,
      final long started) {
    final McpCallResult result = outcome.result();
    final String tag = result.isError() ? "FAILURE" : "SUCCESS";
    final long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    reporter.increment(outcome.nodeId(), "mcp_tool_call", tag);
    if (meters != null) {
      Timer.builder("yukta.mcp.call")
          .tag("tool", outcome.tool())
          .tag("outcome", tag)
          .register(meters)
          .record(Duration.ofMillis(millis));
    }
    log.atDebug()
        .addKeyValue("nodeId", outcome.nodeId())
        .addKeyValue("tool", outcome.tool())
        .addKeyValue("outcome", tag)
        .addKeyValue("latencyMs", millis)
        .log("MCP tool call finished");
    if (result.isError() && failureMode == FailureMode.ERROR) {
      return Flux.error(
          new WorkflowExecutionException(
              "MCP tool " + outcome.tool() + " failed: " + result.error()));
    }
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("success", !result.isError());
    payload.put(TOOL, outcome.tool());
    payload.put("text", result.text());
    payload.put("content", result.content());
    payload.put("structured", result.structured());
    payload.put("error", result.error());
    final Map<String, Object> metadata = new HashMap<>(original.metadata());
    metadata.put("mcpSuccess", !result.isError());
    metadata.put("mcpTool", outcome.tool());
    metadata.put("mcpLatencyMs", millis);
    if (config.get(RESULT_KEY) instanceof final String key && !key.isBlank()) {
      metadata.put(key, result.text());
    }
    final Message<?> output = DefaultMessage.from(original, payload).withMetadata(metadata);
    // from() inherits the upstream port, so always stamp one explicitly
    final String port =
        routeByStatus(config) ? (result.isError() ? PORT_FAILURE : PORT_SUCCESS) : PORT_DEFAULT;
    return Flux.just(output.withSourcePort(port));
  }

  private Mono<McpCallSpec> resolveSpec(
      final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(
            resolve(config.get(URL), message).map(Object::toString),
            resolve(config.get(TOOL), message).map(Object::toString),
            resolveMap(config.get(HEADERS), message),
            resolve(config.get(AUTH_TOKEN), message)
                .map(Object::toString)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty()),
            resolveArguments(config.get(ARGUMENTS), message),
            resolve(config.getOrDefault(TIMEOUT, DEFAULT_TIMEOUT_SECONDS), message))
        .map(
            tuple -> {
              final long timeout = MapUtils.convert(tuple.getT6(), Long.class);
              if (timeout <= 0) {
                throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
              }
              final Map<String, String> headers = new HashMap<>();
              tuple.getT3().forEach((k, v) -> headers.put(k, String.valueOf(v)));
              tuple.getT4().ifPresent(token -> headers.put("Authorization", "Bearer " + token));
              final Object attempt = message.metadata().get(ATTEMPT);
              final Map<String, Object> meta =
                  Map.of(
                      "yukta/messageId",
                      message.id(),
                      "yukta/attempt",
                      attempt == null ? 1 : attempt);
              return new McpCallSpec(
                  tuple.getT1(),
                  tuple.getT2(),
                  tuple.getT5(),
                  headers,
                  meta,
                  Duration.ofSeconds(timeout));
            });
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, Object>> resolveArguments(
      final Object arguments, final Message<?> message) {
    if (arguments == null) {
      final Object payload = message.payload();
      return Mono.just(
          payload instanceof final Map<?, ?> map
              ? (Map<String, Object>) map
              : Map.of("input", String.valueOf(payload)));
    }
    if (arguments instanceof final String expression) {
      return SpelUtils.<Object>evaluate(expression, message, null)
          .map(
              value -> {
                if (value instanceof final Map<?, ?> map) {
                  return (Map<String, Object>) map;
                }
                throw new IllegalArgumentException("arguments must evaluate to a map");
              });
    }
    return resolveMap(arguments, message);
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, Object>> resolveMap(final Object map, final Message<?> message) {
    if (map == null) {
      return Mono.just(Map.of());
    }
    return Flux.fromIterable(((Map<String, Object>) map).entrySet())
        .flatMapSequential(
            entry -> resolve(entry.getValue(), message).map(v -> Map.entry(entry.getKey(), v)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  private Mono<Object> resolve(final Object value, final Message<?> message) {
    if (value == null) {
      return Mono.empty();
    }
    return resolver.resolve(value, message);
  }

  private static boolean routeByStatus(final Map<String, Object> config) {
    return MapUtils.convert(config.getOrDefault(ROUTE_BY_STATUS, false), Boolean.class);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.<Void>fromRunnable(() -> checkConfig(config))
        .doOnError(
            e ->
                log.atWarn()
                    .log("MCP tool call configuration validation failed: {}", e.getMessage()));
  }

  private static void checkConfig(final Map<String, Object> config) {
    if (config.get(URL) == null || config.get(TOOL) == null) {
      throw new IllegalArgumentException("url and tool are mandatory");
    }
    if (config.get(URL) instanceof final String url && !url.contains("${")) {
      final String scheme = URI.create(url).getScheme();
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        throw new IllegalArgumentException("url must be http(s), got: " + url);
      }
    }
    if (config.get(TIMEOUT) instanceof final Number number && number.longValue() <= 0) {
      throw new IllegalArgumentException("timeout must be positive, got: " + number);
    }
    if (config.get(ALLOWED_TOOLS) != null
        && !(config.get(ALLOWED_TOOLS) instanceof final List<?> tools
            && tools.stream().allMatch(t -> t instanceof final String name && !name.isBlank()))) {
      throw new IllegalArgumentException("allowedTools must be a list of tool names");
    }
    ToolApproval.validate(config);
    ToolBudget.validate(config);
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    if (routeByStatus(config) && failureMode != FailureMode.CONTINUE) {
      throw new IllegalArgumentException("routeByStatus requires failureMode: CONTINUE");
    }
  }
}
