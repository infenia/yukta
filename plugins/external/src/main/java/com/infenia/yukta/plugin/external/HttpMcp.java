// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.mcp.McpCallResult;
import com.infenia.yukta.plugin.mcp.McpCallSpec;
import com.infenia.yukta.plugin.mcp.McpClientGateway;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * An MCP server over HTTP, reached through {@link McpClientGateway}: its sessions, SSE replies and
 * {@code yukta.mcp-client.*} limits ({@code allowed-hosts}, {@code allowed-tools}) apply.
 */
@RequiredArgsConstructor
public final class HttpMcp implements McpConnection {

  /** The plugin type, for errors. */
  private final String type;

  /** The endpoint. */
  private final String url;

  /** The longest one request may take. */
  private final Duration timeout;

  /** The MCP client. */
  private final McpClientGateway gateway;

  @Override
  public Mono<Map<String, Object>> tool(final String name) {
    return gateway
        .listTools(spec("", Map.of()), HttpMcp::noHeartbeat)
        .flatMapIterable(tools -> tools)
        .filter(tool -> name.equals(tool.get("name")))
        .next()
        .switchIfEmpty(
            Mono.error(
                new IllegalStateException(
                    "Plugin "
                        + type
                        + ": the MCP server at "
                        + url
                        + " has no tool '"
                        + name
                        + "'")));
  }

  @Override
  public Mono<McpCallResult> call(final String tool, final Map<String, Object> arguments) {
    return gateway.call(spec(tool, arguments), HttpMcp::noHeartbeat);
  }

  @Override
  public void close() {
    // the gateway ends its sessions on shutdown
  }

  /** Progress reports need no heartbeat: the plugin's own timeout bounds each call. */
  private static void noHeartbeat() {
    // nothing to restart
  }

  private McpCallSpec spec(final String tool, final Map<String, Object> arguments) {
    return new McpCallSpec(url, tool, arguments, Map.of(), Map.of(), timeout);
  }
}
