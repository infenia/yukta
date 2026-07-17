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
      "spring.ai.mcp.server.streamable-http.mcp-endpoint=/sse"
    })
class McpEndpointIntegrationTest {

  @LocalServerPort private int port;

  @SneakyThrows
  private String callMcp(final String jsonRpcBody) {
    try (HttpClient client = HttpClient.newHttpClient()) {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/sse"))
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
}
