// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiRole;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** An MCP client on {@code /mcp} with one role's key. Close it to end the session. */
@SuppressWarnings("PMD.ShortClassName")
public final class Mcp implements AutoCloseable {

  private final McpSyncClient client;

  private Mcp(final McpSyncClient client) {
    this.client = client;
  }

  /**
   * Connects and initializes.
   *
   * @param role the role whose key is sent
   * @return the client
   */
  public static Mcp connect(final ApiRole role) {
    final var transport =
        HttpClientStreamableHttpTransport.builder(YuktaServer.baseUrl())
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("X-API-Key", YuktaServer.key(role)))
            .build();
    final McpSyncClient client =
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    client.initialize();
    return new Mcp(client);
  }

  /**
   * The tools the server offers to this key.
   *
   * @return the tools
   */
  public List<McpSchema.Tool> tools() {
    return client.listTools().tools();
  }

  /**
   * Calls a tool and checks that it succeeded.
   *
   * @param name the tool name
   * @param args its arguments
   * @return the result
   */
  public McpSchema.CallToolResult call(final String name, final Map<String, Object> args) {
    final McpSchema.CallToolResult result =
        client.callTool(McpSchema.CallToolRequest.builder().name(name).arguments(args).build());
    assertThat(result.isError()).as("%s failed: %s", name, result.content()).isNotEqualTo(true);
    return result;
  }

  /**
   * Calls a tool and checks that it reports an error.
   *
   * @param name the tool name
   * @param args its arguments
   * @return the error result
   */
  public McpSchema.CallToolResult callExpectingError(
      final String name, final Map<String, Object> args) {
    final McpSchema.CallToolResult result =
        client.callTool(McpSchema.CallToolRequest.builder().name(name).arguments(args).build());
    assertThat(result.isError()).as("%s should have failed", name).isTrue();
    return result;
  }

  @Override
  public void close() {
    client.close();
  }
}
