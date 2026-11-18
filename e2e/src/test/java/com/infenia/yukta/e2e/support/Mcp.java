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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An MCP client on {@code /mcp} with one role's key. Close it to end the session. */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortClassName",
  "PMD.TooManyMethods"
})
public final class Mcp implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final McpSyncClient client;

  private Mcp(final McpSyncClient client) {
    this.client = client;
  }

  /**
   * Connects with an API key given as text (for example the session-scoped key).
   *
   * @param key the key to send
   * @return the client
   */
  public static Mcp connect(final String key) {
    final var transport =
        HttpClientStreamableHttpTransport.builder(YuktaServer.baseUrl())
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("X-API-Key", key))
            .build();
    final McpSyncClient client =
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    client.initialize();
    return new Mcp(client);
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

  /**
   * The result of a call as JSON: its structured content, else its text parsed.
   *
   * @param result a tool result
   * @return the JSON
   */
  public static JsonNode json(final McpSchema.CallToolResult result) {
    return result.structuredContent() == null
        ? JSON.readTree(text(result))
        : JSON.valueToTree(result.structuredContent());
  }

  /**
   * The text of a result.
   *
   * @param result a tool result
   * @return the text of its first content part, or an empty string
   */
  public static String text(final McpSchema.CallToolResult result) {
    return result.content().isEmpty()
            || !(result.content().get(0) instanceof McpSchema.TextContent t)
        ? ""
        : t.text();
  }

  /**
   * The prompts the server offers.
   *
   * @return the prompts
   */
  public List<McpSchema.Prompt> prompts() {
    return client.listPrompts().prompts();
  }

  /**
   * Renders a prompt.
   *
   * @param name the prompt
   * @param args its arguments
   * @return the rendered messages
   */
  public McpSchema.GetPromptResult prompt(final String name, final Map<String, Object> args) {
    return client.getPrompt(new McpSchema.GetPromptRequest(name, args));
  }

  /**
   * The resources the server offers.
   *
   * @return the resources
   */
  public List<McpSchema.Resource> resources() {
    return client.listResources().resources();
  }

  /**
   * Reads a resource.
   *
   * @param uri the resource URI
   * @return the text of its first part
   */
  public String read(final String uri) {
    final var contents = client.readResource(new McpSchema.ReadResourceRequest(uri)).contents();
    return contents.get(0) instanceof McpSchema.TextResourceContents text ? text.text() : "";
  }

  @Override
  public void close() {
    client.close();
  }
}
