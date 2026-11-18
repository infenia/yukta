// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Mcp;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import com.infenia.yukta.security.ApiRole;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A run asks for an approval; an agent lists it, escalates it and answers it. The notifications it
 * raises are read and marked read.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
class McpInputsE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  private static JsonNode call(final Mcp mcp, final String tool, final Map<String, Object> args) {
    return Mcp.json(mcp.call(tool, args));
  }

  /** Starts an asking run through REST (the session is cleaned up) and returns its ids. */
  private static String[] asking(final Scenarios scenarios) {
    final String session = Scenarios.unique("mcpinput");
    scenarios.applySession(Workflows.asking(session));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    return new String[] {session, execution};
  }

  private static JsonNode awaitInput(final Mcp mcp, final String session) {
    final JsonNode[] found = new JsonNode[1];
    await()
        .atMost(WAIT)
        .until(() -> {
          final JsonNode inputs =
              call(mcp, "list_pending_inputs", Map.of("sessionId", session)).path("inputs");
          if (!inputs.isEmpty()) {
            found[0] = inputs.get(0);
          }
          return found[0] != null;
        });
    return found[0];
  }

  @Test
  void pendingApprovalIsEscalatedThenAnswered(final Api api, final Scenarios scenarios) {
    final String[] run = asking(scenarios);
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final JsonNode input = awaitInput(mcp, run[0]);
      assertThat(input.path("state").asString()).isEqualTo("OPEN");
      assertThat(input.path("kind").asString()).isEqualTo("APPROVAL");
      final Map<String, Object> request =
          Map.of(
              "sessionId",
              run[0],
              "executionId",
              run[1],
              "requestId",
              input.path("requestId").asString());

      assertThat(
              call(mcp, "escalate_input", with(request, "reason", "needs a person"))
                  .path("action")
                  .asString())
          .isEqualTo("ESCALATE_INPUT");
      assertThat(
              call(mcp, "answer_input", with(request, "decision", "approve"))
                  .path("message")
                  .asString())
          .contains("approve");

      assertThat(
              call(
                      mcp,
                      "get_workflow_status",
                      Map.of("sessionId", run[0], "executionId", run[1], "waitSeconds", 20))
                  .path("status")
                  .asString())
          .isEqualTo("SUCCESS");
    }
  }

  @Test
  void answerThatIsNotAnOptionIsToolError(final Api api, final Scenarios scenarios) {
    final String[] run = asking(scenarios);
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final JsonNode input = awaitInput(mcp, run[0]);

      final McpSchema.CallToolResult refused =
          mcp.callExpectingError(
              "answer_input",
              Map.of(
                  "sessionId",
                  run[0],
                  "executionId",
                  run[1],
                  "requestId",
                  input.path("requestId").asString(),
                  "decision",
                  "maybe"));

      assertThat(Mcp.text(refused)).contains("approve");
      api.call("POST", "/api/workflow/" + run[0] + "/w/stop?mode=CANCEL", null);
    }
  }

  @Test
  void theNotificationsAnInputRaisedAreListedAndMarkedRead(
      final Api api, final Scenarios scenarios) {
    final String[] run = asking(scenarios);
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final JsonNode input = awaitInput(mcp, run[0]);
      call(
          mcp,
          "escalate_input",
          Map.of(
              "sessionId",
              run[0],
              "executionId",
              run[1],
              "requestId",
              input.path("requestId").asString(),
              "reason",
              "look"));

      final JsonNode[] mine = new JsonNode[1];
      await()
          .atMost(WAIT)
          .until(() -> {
            for (final JsonNode n :
                call(mcp, "list_notifications", Map.of()).path("notifications")) {
              if (run[1].equals(n.path("executionId").asString())) {
                mine[0] = n;
              }
            }
            return mine[0] != null;
          });

      assertThat(mine[0].path("category").asString()).isEqualTo("input");
      final JsonNode marked =
          call(
              mcp,
              "mark_notifications_read",
              Map.of("ids", java.util.List.of(mine[0].path("id").asString())));
      assertThat(marked.toString()).contains("1");
      assertThat(mcp.callExpectingError("mark_notifications_read", Map.of()).content())
          .isNotEmpty();
      api.call("POST", "/api/workflow/" + run[0] + "/w/stop?mode=CANCEL", null);
    }
  }

  private static Map<String, Object> with(
      final Map<String, Object> base, final String key, final Object value) {
    final Map<String, Object> copy = new java.util.concurrent.ConcurrentHashMap<>(base);
    copy.put(key, value);
    return copy;
  }
}
