// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import java.time.Duration;
import java.util.Map;

/**
 * A resolved MCP tool call.
 *
 * @param url MCP server endpoint (streamable HTTP)
 * @param tool tool name
 * @param arguments tool arguments
 * @param headers HTTP headers sent on every request, including any authorization
 * @param meta values sent as the request's {@code _meta}, e.g. an idempotency key
 * @param timeout time limit for the whole call, including the session handshake
 */
public record McpCallSpec(
    String url,
    String tool,
    Map<String, Object> arguments,
    Map<String, String> headers,
    Map<String, Object> meta,
    Duration timeout) {

  /** Defensively copies the maps. */
  public McpCallSpec {
    arguments = Map.copyOf(arguments);
    headers = Map.copyOf(headers);
    meta = Map.copyOf(meta);
  }

  /**
   * Key under which the server session is cached: the same endpoint and credentials share one. The
   * headers enter as themselves (sorted, each name and value after its length), not as a hash, so
   * two different credentials can never share a session by a collision. The key lives only in the
   * session cache; it is not logged.
   *
   * @return the session cache key
   */
  public String sessionKey() {
    final StringBuilder key = new StringBuilder(url).append('#');
    headers.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            header ->
                key.append(header.getKey().length())
                    .append(':')
                    .append(header.getKey())
                    .append(header.getValue().length())
                    .append(':')
                    .append(header.getValue()));
    return key.toString();
  }
}
