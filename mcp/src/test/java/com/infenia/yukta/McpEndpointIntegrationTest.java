// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end smoke test of the MCP endpoint: exercises the stateless streamable-HTTP transport with
 * real JSON-RPC calls against the running reactive stack.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.ai.mcp.server.name=yukta",
      "spring.ai.mcp.server.protocol=STATELESS",
      "spring.ai.mcp.server.type=ASYNC",
      "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp"
    })
class McpEndpointIntegrationTest {

  @LocalServerPort private int port;

  @SneakyThrows
  private String callMcp(final String jsonRpcBody) {
    return callMcp("/mcp", jsonRpcBody);
  }

  @SneakyThrows
  private String callMcp(final String path, final String jsonRpcBody) {
    try (HttpClient client = HttpClient.newHttpClient()) {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + path))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody))
              .build();
      final HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isBetween(200, 299);
      return response.body();
    }
  }

  @Test
  void testToolsListExposesModernizedToolSurface() {
    final String response =
        callMcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");

    assertThat(response)
        .contains("start_workflow")
        .contains("control_workflow")
        .contains("control_node")
        .contains("get_workflow_status")
        .contains("get_workflow_history")
        .contains("get_execution_logs")
        .contains("get_workflow_details")
        .contains("get_session_details")
        .contains("list_sessions")
        .contains("create_session")
        .contains("list_plugins")
        .contains("get_plugin_details")
        .contains("get_control_bus_status")
        .contains("get_session_creation_instructions")
        .contains("get_plugin_creation_guide");
    assertThat(response)
        .doesNotContain("trigger_workflow")
        .doesNotContain("stream_session_logs")
        .doesNotContain("get_workflow_execution_logs");
    assertThat(response).contains("outputSchema").contains("readOnlyHint");
  }

  @Test
  void testOutputSchemasAllowTheFieldsAnEmptyRunLacks() {
    final JsonNode tools =
        JsonMapper.builder()
            .build()
            .readTree(
                callMcp("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}"))
            .at("/result/tools");

    for (final JsonNode tool : tools) {
      final String name = tool.path("name").asString();
      if ("get_workflow_status".equals(name) || "get_execution_tree".equals(name)) {
        for (final String field : new String[] {"parentExecutionId", "restartOf", "endTime"}) {
          assertThat(requiredNonNull(tool.path("outputSchema"), field))
              .as("%s.%s must accept null", name, field)
              .isFalse();
        }
      }
    }
  }

  /** Whether some object in the schema lists {@code field} as required and does not allow null. */
  private static boolean requiredNonNull(final JsonNode schema, final String field) {
    boolean found = false;
    final JsonNode property = schema.path("properties").path(field);
    if (!property.isMissingNode()) {
      final boolean required =
          schema.path("required").valueStream().anyMatch(r -> field.equals(r.asString()));
      found = required && !property.toString().contains("null");
    }
    for (final JsonNode child : schema) {
      found = found || ((child.isObject() || child.isArray()) && requiredNonNull(child, field));
    }
    return found;
  }

  @Test
  void testUnknownExecutionYieldsActionableToolError() {
    final String response =
        callMcp(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_workflow_status\",\"arguments\":"
                + "{\"sessionId\":\"nope\",\"executionId\":\"nope\"}}}");

    assertThat(response).contains("isError").contains("Execution not found: nope");
  }

  @Test
  void testListSessionsReturnsFullArray() {
    final String response =
        callMcp(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"list_sessions\",\"arguments\":{}}}");

    assertThat(response).doesNotContain("\"isError\":true");
    assertThat(response).contains("\"result\"").contains("sessions");
  }

  @Test
  void testLegacySseEndpointStillServes() {
    final String response =
        callMcp("/sse", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");

    assertThat(response).contains("list_sessions");
  }
}
