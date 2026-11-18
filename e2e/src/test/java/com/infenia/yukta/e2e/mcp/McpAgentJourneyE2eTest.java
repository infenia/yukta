// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Mcp;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An agent at work: create a session, run it, read it every way, control a held run, and move a
 * workflow between versions.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class McpAgentJourneyE2eTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration WAIT = Duration.ofSeconds(30);

  /** The config a session is created from: the JSON of the definition. */
  private static Map<String, Object> config(final String json) {
    return JSON.readValue(json, new tools.jackson.core.type.TypeReference<>() {});
  }

  private static JsonNode call(final Mcp mcp, final String tool, final Map<String, Object> args) {
    return Mcp.json(mcp.call(tool, args));
  }

  private static String create(final Mcp mcp, final Scenarios scenarios, final String json) {
    final Map<String, Object> config = config(json);
    final String session = (String) config.get("sessionId");
    config.put("tags", Map.of());
    assertThat(call(mcp, "create_session", Map.of("config", config)).path("success").asBoolean())
        .isTrue();
    scenarios.track(session);
    return session;
  }

  @Test
  void runIsStartedAndReadEveryWay(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String session =
          create(mcp, scenarios, Workflows.echo(Scenarios.unique("mcp"), "said-by-mcp"));

      final String execution =
          call(mcp, "start_workflow", Map.of("sessionId", session, "workflowId", "w"))
              .path("executionId")
              .asString();
      final JsonNode status =
          call(
              mcp,
              "get_workflow_status",
              Map.of("sessionId", session, "executionId", execution, "waitSeconds", 20));

      assertThat(status.path("status").asString()).isEqualTo("SUCCESS");
      assertThat(
              call(
                      mcp,
                      "get_execution_tree",
                      Map.of("sessionId", session, "executionId", execution))
                  .path("executions"))
          .isNotEmpty();
      assertThat(
              call(
                      mcp,
                      "get_node_output",
                      Map.of("sessionId", session, "executionId", execution, "nodeId", "p"))
                  .toString())
          .contains("said-by-mcp");
      assertThat(
              call(
                      mcp,
                      "get_execution_logs",
                      Map.of("sessionId", session, "executionId", execution))
                  .toString())
          .contains("said-by-mcp");
      assertThat(
              call(
                      mcp,
                      "get_execution_result",
                      Map.of("sessionId", session, "executionId", execution))
                  .path("complete")
                  .asBoolean())
          .isTrue();
      assertThat(call(mcp, "get_workflow_history", Map.of("sessionId", session)).toString())
          .contains(execution);
      assertThat(
              call(mcp, "get_session_details", Map.of("sessionId", session))
                  .path("workflowIds")
                  .get(0)
                  .asString())
          .isEqualTo("w");
      assertThat(
              call(mcp, "get_workflow_details", Map.of("sessionId", session, "workflowId", "w"))
                  .path("nodes"))
          .hasSize(3);
      assertThat(call(mcp, "list_sessions", Map.of()).toString()).contains(session);
    }
  }

  @Test
  void heldRunIsPausedResumedSteppedAndStopped(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String session = create(mcp, scenarios, Workflows.sleeper(Scenarios.unique("mcp"), 30));
      final String execution =
          call(mcp, "start_workflow", Map.of("sessionId", session, "workflowId", "w"))
              .path("executionId")
              .asString();
      final Map<String, Object> run = Map.of("sessionId", session, "executionId", execution);

      for (final String action : new String[] {"PAUSE", "RESUME"}) {
        assertThat(
                call(mcp, "control_workflow", with(run, "action", action))
                    .path("action")
                    .asString())
            .isEqualTo(action);
      }
      for (final String action : new String[] {"PAUSE", "RESUME"}) {
        assertThat(
                call(mcp, "control_node", with(with(run, "nodeId", "s"), "action", action))
                    .path("nodeId")
                    .asString())
            .isEqualTo("s");
      }
      assertThat(
              call(
                      mcp,
                      "set_node_breakpoint",
                      with(
                          with(with(run, "nodeId", "e"), "field", "payload.status"),
                          "equalsValue",
                          "error"))
                  .path("action")
                  .asString())
          .isEqualTo("BREAKPOINT_SET");

      call(mcp, "control_workflow", with(with(run, "action", "STOP"), "mode", "CANCEL"));
      await()
          .atMost(WAIT)
          .untilAsserted(() ->
              assertThat(call(mcp, "get_workflow_status", run).path("status").asString())
                  .isIn("STOPPED", "CANCELLED"));
    }
  }

  private static Map<String, Object> with(
      final Map<String, Object> base, final String key, final Object value) {
    final Map<String, Object> copy = new java.util.concurrent.ConcurrentHashMap<>(base);
    copy.put(key, value);
    return copy;
  }

  @Test
  void workflowIsComparedAndRolledBackToAnEarlierVersion(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String id = Scenarios.unique("mcp");
      final String session = create(mcp, scenarios, Workflows.echo(id, "version-one"));
      final Map<String, Object> second = config(Workflows.echo(id, "version-two"));
      second.put("tags", Map.of());
      call(mcp, "create_session", Map.of("config", second, "overwrite", true));
      final Map<String, Object> workflow = Map.of("sessionId", session, "workflowId", "w");

      final JsonNode versions = call(mcp, "list_workflow_versions", workflow).path("versions");
      assertThat(versions.size()).isGreaterThanOrEqualTo(2);
      final String oldest = versions.get(versions.size() - 1).path("id").asString();

      assertThat(
              call(mcp, "diff_workflow_versions", with(workflow, "olderVersion", oldest))
                  .path("changedNodes")
                  .toString())
          .contains("p");
      assertThat(
              call(
                      mcp,
                      "note_workflow_version",
                      with(with(workflow, "version", oldest), "note", "first"))
                  .path("note")
                  .asString())
          .isEqualTo("first");
      call(mcp, "rollback_workflow", with(workflow, "version", oldest));
      assertThat(call(mcp, "get_workflow_details", workflow).toString()).contains("version-one");
    }
  }

  @Test
  void deletingWorkflowAndSessionIsAnAdminAction(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String session = create(mcp, scenarios, Workflows.echo(Scenarios.unique("mcp"), "x"));

      assertThat(
              call(mcp, "delete_workflow", Map.of("sessionId", session, "workflowId", "w"))
                  .path("action")
                  .asString())
          .isEqualTo("DELETE_WORKFLOW");
      assertThat(
              call(mcp, "delete_session", Map.of("sessionId", session)).path("action").asString())
          .isEqualTo("DELETE_SESSION");
    }
  }

  @Test
  void theCatalogsAnAgentReadsBeforeItWrites() {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      assertThat(call(mcp, "list_plugins", Map.of()).toString()).contains("MAPPER");
      assertThat(call(mcp, "get_plugin_details", Map.of("type", "MAPPER")).path("type").asString())
          .isEqualTo("MAPPER");
      assertThat(call(mcp, "list_ai_backends", Map.of()).toString()).contains("kind");
      assertThat(call(mcp, "get_session_creation_instructions", Map.of()).has("namingConventions"))
          .isTrue();
      assertThat(call(mcp, "get_plugin_creation_guide", Map.of()).has("architectureOverview"))
          .isTrue();
      assertThat(call(mcp, "whoami", Map.of()).path("role").asString()).isEqualTo("ADMIN");
      assertThat(call(mcp, "get_scheduler_limits", Map.of()).has("maxConcurrent")).isTrue();
      assertThat(call(mcp, "get_control_bus_status", Map.of()).isObject()).isTrue();
      assertThat(call(mcp, "get_metrics_snapshot", Map.of()).isObject()).isTrue();
    }
  }
}
