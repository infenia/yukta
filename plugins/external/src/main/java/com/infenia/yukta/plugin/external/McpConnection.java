// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.mcp.McpCallResult;
import java.util.Map;
import reactor.core.publisher.Mono;

/** The MCP server of a {@code run.mcp} plugin: a process over stdio, or an HTTP endpoint. */
public interface McpConnection extends AutoCloseable {

  /**
   * Finds a tool of the server.
   *
   * @param name the tool's name
   * @return {@code {name, description, inputSchema}}, or an error when the server has no such tool
   */
  Mono<Map<String, Object>> tool(String name);

  /**
   * Calls a tool.
   *
   * @param tool the tool's name
   * @param arguments the arguments
   * @return the result; a tool error is a result with {@code isError}
   */
  Mono<McpCallResult> call(String tool, Map<String, Object> arguments);

  /** Stops the server process, if any. */
  @Override
  void close();
}
