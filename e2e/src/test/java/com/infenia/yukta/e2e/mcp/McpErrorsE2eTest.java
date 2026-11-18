// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Mcp;
import com.infenia.yukta.security.ApiRole;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A failure comes back as a tool result with {@code isError: true} and a message an agent can act
 * on, not as a protocol error. (Oversized and malformed bodies are in {@code BoundaryFuzzE2eTest}.)
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidInstantiatingObjectsInLoops"})
class McpErrorsE2eTest {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private static Map<String, Object> ids(final String... keys) {
    final Map<String, Object> args = new java.util.concurrent.ConcurrentHashMap<>();
    for (final String key : keys) {
      args.put(key, "e2e-nope");
    }
    return args;
  }

  @Test
  void unknownIdIsToolErrorThatSaysWhatIsMissing() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      assertThat(
              Mcp.text(
                  mcp.callExpectingError("get_workflow_status", ids("sessionId", "executionId"))))
          .contains("Execution not found: e2e-nope");
      assertThat(Mcp.text(mcp.callExpectingError("get_session_details", ids("sessionId"))))
          .contains("Session not found");
      assertThat(
              Mcp.text(
                  mcp.callExpectingError("get_workflow_details", ids("sessionId", "workflowId"))))
          .contains("Workflow not found");
      assertThat(Mcp.text(mcp.callExpectingError("start_workflow", ids("sessionId", "workflowId"))))
          .contains("not found");
      assertThat(Mcp.text(mcp.callExpectingError("get_supervisor", ids("id"))))
          .contains("Unknown supervisor");
      assertThat(Mcp.text(mcp.callExpectingError("get_rules", ids("supervisorId"))))
          .contains("not found");
      assertThat(
              Mcp.text(
                  mcp.callExpectingError("get_plugin_details", Map.of("type", "NO_SUCH_PLUGIN"))))
          .isNotBlank();
    }
  }

  @Test
  void missingArgumentIsToolErrorThatNamesIt() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      assertThat(Mcp.text(mcp.callExpectingError("get_workflow_status", Map.of("sessionId", "s"))))
          .contains("input validation failed", "executionId");
    }
  }

  @Test
  void unknownToolIsAnError() {
    final Reply reply =
        Api.reply(
            Api.forRole(ApiRole.ADMIN)
                .client()
                .post()
                .uri("/mcp")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .bodyValue(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}")
                .exchange());

    assertThat(reply.status()).isBetween(200, 499);
    assertThat(reply.body()).contains("no_such_tool").doesNotContain("Exception", "at com.");
  }

  @Test
  void resultWithNullFieldsPassesTheOutputCheck() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final JsonNode me = Mcp.json(mcp.call("whoami", Map.of()));

      assertThat(me.has("role")).isTrue();
      assertThat(Mcp.json(mcp.call("list_sessions", Map.of())).has("sessions")).isTrue();
    }
  }

  @Test
  void theAgentConversationToolsRefuseAnUnknownConversation() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      assertThat(
              Mcp.json(mcp.call("list_agent_conversations", Map.of()))
                  .path("conversations")
                  .isArray())
          .isTrue();
      final List<String> tools =
          List.of(
              "get_agent_conversation",
              "read_agent_screen",
              "send_agent_message",
              "interrupt_agent",
              "end_agent_conversation",
              "send_agent_keys");
      for (final McpSchema.Tool tool : mcp.tools()) {
        if (tools.contains(tool.name())) {
          final Map<String, Object> args = new java.util.concurrent.ConcurrentHashMap<>();
          for (final JsonNode required : JSON.valueToTree(tool.inputSchema()).path("required")) {
            args.put(required.asString(), "e2e-nope");
          }
          assertThat(Mcp.text(mcp.callExpectingError(tool.name(), args)))
              .as(tool.name())
              .isNotBlank();
        }
      }
    }
  }
}
