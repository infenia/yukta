// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Asks an MCP server which tools it offers ({@code tools/list}) for every message and emits {@code
 * {tools: [{name, description, inputSchema}], count}}. Feed {@code payload.tools} to the {@code
 * tools} option of an {@code LLM_CALL} node so the model can choose among them, then send its calls
 * to an {@code MCP_TOOL_CALL} node. {@code allowedTools} keeps only the listed tools.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class McpListToolsPlugin implements ProcessorPlugin {

  /** Config key: the MCP endpoint. */
  private static final String URL = "url";

  /** Config key: extra request headers. */
  private static final String HEADERS = "headers";

  /** Config key: bearer token. */
  private static final String AUTH_TOKEN = "authToken";

  /** Config key: time limit in seconds. */
  private static final String TIMEOUT = "timeout";

  /** Config key: tools to keep. */
  private static final String ALLOWED_TOOLS = "allowedTools";

  /** Time limit used when none is configured. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 30L;

  /** The MCP client. */
  private final McpClientGateway gateway;

  /** Resolves variable expressions and secrets. */
  private final VariableResolver resolver;

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Lists the tools of a remote MCP server so a model can choose among them.",
        """
        Configure with:
        - url: MCP server endpoint (streamable HTTP), required.
        - headers, authToken: as for MCP_TOOL_CALL.
        - timeout: seconds, default 30.
        - allowedTools: keep only these tools.
        Emits {tools: [{name, description, inputSchema}], count}; only the first page the server
        returns is listed.
        """,
        Optional.empty());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(URL, ConfigField.Type.STRING, "MCP server endpoint; ${...} supported"),
        ConfigField.optional(HEADERS, ConfigField.Type.MAP, null, "Extra request headers"),
        ConfigField.optional(
            AUTH_TOKEN, ConfigField.Type.SECRET, null, "Bearer token (decrypted:<key>)"),
        ConfigField.optional(
            TIMEOUT, ConfigField.Type.NUMBER, DEFAULT_TIMEOUT_SECONDS, "Timeout in seconds"),
        ConfigField.optional(ALLOWED_TOOLS, ConfigField.Type.LIST, null, "Tools to keep"));
  }

  @Override
  public String getType() {
    return "MCP_LIST_TOOLS";
  }

  @Override
  public Duration getDefaultTimeout(final Map<String, Object> config) {
    return Duration.ofSeconds(timeoutSeconds(config) + 5);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          if (!(config.get(URL) instanceof final String url) || url.isBlank()) {
            throw new IllegalArgumentException("url is mandatory");
          }
          if (!url.contains("${")) {
            final String scheme = URI.create(url).getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
              throw new IllegalArgumentException("url must be http(s), got: " + url);
            }
          }
          timeoutSeconds(config);
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.concatMap(message -> list(message, config));
  }

  private Mono<Message<?>> list(final Message<?> message, final Map<String, Object> config) {
    return spec(message, config)
        .flatMap(spec -> gateway.listTools(spec, () -> {}))
        .map(tools -> keep(tools, config))
        .map(tools -> output(message, tools));
  }

  private static List<Map<String, Object>> keep(
      final List<Map<String, Object>> tools, final Map<String, Object> config) {
    return config.get(ALLOWED_TOOLS) instanceof final List<?> allowed && !allowed.isEmpty()
        ? tools.stream().filter(tool -> allowed.contains(tool.get("name"))).toList()
        : tools;
  }

  private static Message<?> output(
      final Message<?> original, final List<Map<String, Object>> tools) {
    final Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tools", tools);
    payload.put("count", tools.size());
    return DefaultMessage.from(original, payload);
  }

  private Mono<McpCallSpec> spec(final Message<?> message, final Map<String, Object> config) {
    return Mono.zip(
            resolver.resolve(config.get(URL), message).map(Object::toString),
            resolveHeaders(config.get(HEADERS), message),
            resolver.resolve(config.getOrDefault(AUTH_TOKEN, ""), message).map(Object::toString))
        .map(
            tuple -> {
              final Map<String, String> headers = new HashMap<>(tuple.getT2());
              if (!tuple.getT3().isEmpty()) {
                headers.put("Authorization", "Bearer " + tuple.getT3());
              }
              return new McpCallSpec(
                  tuple.getT1(),
                  "tools/list",
                  Map.of(),
                  headers,
                  Map.of(),
                  Duration.ofSeconds(timeoutSeconds(config)));
            });
  }

  @SuppressWarnings("unchecked")
  private Mono<Map<String, String>> resolveHeaders(
      final Object configured, final Message<?> message) {
    return configured instanceof final Map<?, ?> map
        ? Flux.fromIterable(((Map<String, Object>) map).entrySet())
            .flatMapSequential(
                entry ->
                    resolver
                        .resolve(entry.getValue(), message)
                        .map(value -> Map.entry(entry.getKey(), String.valueOf(value))))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        : Mono.just(Map.of());
  }

  private static long timeoutSeconds(final Map<String, Object> config) {
    final long seconds =
        MapUtils.convert(config.getOrDefault(TIMEOUT, DEFAULT_TIMEOUT_SECONDS), Long.class);
    if (seconds <= 0) {
      throw new IllegalArgumentException("timeout must be positive, got: " + seconds);
    }
    return seconds;
  }
}
