// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.VariableResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Processor plugin performing an HTTP request per input message.
 *
 * <p>Typical uses: fetching live data to inject into an LLM prompt, or calling a tool endpoint.
 * Config values (url, method, headers, body) support variable expressions and {@code decrypted:}
 * secrets.
 */
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.OnlyOneReturn",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
@Slf4j
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The gateway is a Spring service that the plugin only calls")
public final class HttpRequestPlugin implements ProcessorPlugin {

  /** Extra time the node gets beyond the request timeout, in seconds. */
  private static final long MARGIN_SECONDS = 5L;

  private static final String TYPE = "HTTP_REQUEST";
  private static final String URL = "url";
  private static final String METHOD = "method";
  private static final String HEADERS = "headers";
  private static final String BODY = "body";
  private static final String TIMEOUT = "timeout";

  /** Requests in flight at once when the node does not set {@code concurrency}. */
  private static final int DEFAULT_CONCURRENCY = 16;

  private static final String FAILURE_MODE = "failureMode";
  private static final String ROUTE_BY_STATUS = "routeByStatus";
  private static final String PORT_SUCCESS = "success";
  private static final String PORT_FAILURE = "failure";
  private static final String PORT_DEFAULT = "default";
  private static final String STATUS_KEY = "status";
  private static final long DEFAULT_TIMEOUT_SECONDS = 30L;

  private final HttpRequestGateway gateway;
  private final VariableResolver resolver;
  private final PluginMetricsReporter reporter;
  private final ObjectMapper json;

  /**
   * Creates the plugin without metrics.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   */
  public HttpRequestPlugin(final HttpRequestGateway gateway, final VariableResolver resolver) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = PluginMetricsReporter.NOOP;
    this.json = new ObjectMapper();
  }

  /**
   * Spring constructor.
   *
   * @param gateway the gateway
   * @param resolver resolves config expressions
   * @param reporterProvider optional metrics reporter
   * @param json the application JSON mapper
   */
  @Autowired
  public HttpRequestPlugin(
      final HttpRequestGateway gateway,
      final VariableResolver resolver,
      final ObjectProvider<PluginMetricsReporter> reporterProvider,
      final ObjectMapper json) {
    this.gateway = gateway;
    this.resolver = resolver;
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
    this.json = json;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Performs an HTTP request per message and emits {status, success, headers, body}. "
            + "Use it to fetch live data for prompts or to call tool endpoints.",
        usagePattern(),
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required("url", ConfigField.Type.STRING, "Request URL; ${...} supported"),
        ConfigField.choice(
            "method", false, "GET", "HTTP method", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"),
        ConfigField.optional("headers", ConfigField.Type.MAP, null, "Request headers"),
        ConfigField.optional("body", ConfigField.Type.EXPRESSION, null, "Request body"),
        ConfigField.optional("timeout", ConfigField.Type.NUMBER, 30, "Timeout in seconds"),
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
            "Requests in flight at once"));
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
    - url: String (variable expressions supported)
      Example: "https://api.example.com/cart/${payload.userId}"

    OPTIONAL:
    - method: GET | POST | PUT | PATCH | DELETE | HEAD (default: GET)
    - headers: Map<String, String> (variable expressions and decrypted:<key> supported)
      Example: {"Authorization": "decrypted:crm-token"}
    - body: String or object (strings support variable expressions); sent as-is / as JSON
    - timeout: seconds, positive (default: 30)
    - concurrency: requests in flight at once, positive (default: 16)
    - failureMode: ERROR | CONTINUE (default: ERROR)
      ERROR: non-2xx status or transport error fails the node
      CONTINUE: the result is emitted (status 0 on transport errors) so downstream can route
    - routeByStatus: Boolean (default: false, requires failureMode: CONTINUE)
      Stamps messages with source port "success" (2xx) or "failure" (anything else)

    OUTPUT:
    - payload = {status, success, headers, body}; body is parsed when the response is JSON
    - Message metadata contains "status"
    """;
  }

  /**
   * The node timeout follows the request timeout, plus a margin.
   *
   * @param config the node configuration
   * @return the configured (or default) request timeout plus a margin
   */
  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    final long seconds =
        config.get(TIMEOUT) instanceof final Number number && number.longValue() > 0
            ? number.longValue()
            : DEFAULT_TIMEOUT_SECONDS;
    return Duration.ofSeconds(seconds + MARGIN_SECONDS);
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    if (routeByStatus(config)) {
      return List.of(PORT_SUCCESS, PORT_FAILURE);
    }
    return getOutputPorts();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return PluginGuard.counted(
        input.flatMapSequential(
            message -> request(message, config),
            ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY)),
        reporter,
        "http");
  }

  private Flux<Message<?>> request(final Message<?> message, final Map<String, Object> config) {
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    return resolveSpec(message, config)
        .flatMap(
            spec ->
                gateway
                    .execute(spec)
                    .onErrorResume(
                        error ->
                            failureMode == FailureMode.CONTINUE
                                ? Mono.just(HttpRequestResult.transportFailure(error.toString()))
                                : Mono.error(error)))
        .flatMapMany(result -> toMessage(message, config, failureMode, result));
  }

  private Flux<Message<?>> toMessage(
      final Message<?> original,
      final Map<String, Object> config,
      final FailureMode failureMode,
      final HttpRequestResult result) {
    if (!result.isSuccess() && failureMode == FailureMode.ERROR) {
      return Flux.error(
          new WorkflowExecutionException(
              "HTTP request failed with status " + result.status() + ": " + result.body()));
    }
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(STATUS_KEY, result.status());
    payload.put("success", result.isSuccess());
    payload.put("headers", result.headers());
    payload.put(BODY, parseBody(result));
    final Map<String, Object> metadata = new HashMap<>(original.metadata());
    metadata.put(STATUS_KEY, result.status());
    final Message<?> output = DefaultMessage.from(original, payload).withMetadata(metadata);
    // from() inherits the upstream port, so always stamp one explicitly
    final String port =
        routeByStatus(config) ? (result.isSuccess() ? PORT_SUCCESS : PORT_FAILURE) : PORT_DEFAULT;
    return Flux.just(output.withSourcePort(port));
  }

  private Object parseBody(final HttpRequestResult result) {
    if (!result.json()) {
      return result.body();
    }
    try {
      return json.readValue(result.body(), Object.class);
    } catch (JacksonException e) {
      log.atWarn().setCause(e).log("Response declared JSON but is not valid; keeping raw text");
      return result.body();
    }
  }

  private Mono<HttpRequestSpec> resolveSpec(
      final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(
            resolve(config.get(URL), message).map(Object::toString),
            resolve(config.getOrDefault(METHOD, "GET"), message).map(Object::toString),
            resolveHeaders(config.get(HEADERS), message),
            resolve(config.getOrDefault(TIMEOUT, DEFAULT_TIMEOUT_SECONDS), message),
            resolve(config.get(BODY), message)
                .map(Optional::<Object>of)
                .defaultIfEmpty(Optional.empty()))
        .map(
            tuple -> {
              final long timeout = MapUtils.convert(tuple.getT4(), Long.class);
              if (timeout <= 0) {
                throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
              }
              final Object body = tuple.getT5().orElse(null);
              return new HttpRequestSpec(
                  tuple.getT2().toUpperCase(Locale.ROOT),
                  tuple.getT1(),
                  tuple.getT3(),
                  body,
                  Duration.ofSeconds(timeout));
            });
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, String>> resolveHeaders(final Object headers, final Message<?> message) {
    if (headers == null) {
      return Mono.just(Map.of());
    }
    return Flux.fromIterable(((Map<String, Object>) headers).entrySet())
        .flatMapSequential(
            entry ->
                resolve(entry.getValue(), message)
                    .map(value -> Map.entry(entry.getKey(), value.toString())))
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
                    .log("HTTP request configuration validation failed: {}", e.getMessage()));
  }

  private static void checkConfig(final Map<String, Object> config) {
    if (config.get(URL) == null) {
      throw new IllegalArgumentException("url is mandatory");
    }
    if (config.get(TIMEOUT) instanceof final Number number && number.longValue() <= 0) {
      throw new IllegalArgumentException("timeout must be positive, got: " + number);
    }
    ConfigValidation.concurrency(config, DEFAULT_CONCURRENCY);
    final FailureMode failureMode = FailureMode.from(config.get(FAILURE_MODE));
    if (routeByStatus(config) && failureMode != FailureMode.CONTINUE) {
      throw new IllegalArgumentException("routeByStatus requires failureMode: CONTINUE");
    }
  }
}
