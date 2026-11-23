// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.YuktaServer;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Happy path of the MCP client plugins, against Yukta's own MCP endpoint. */
@E2eTest
@NoArgsConstructor
class McpPluginsE2eTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static void requirePlugin(final Api api, final String type) {
    assumeThat(api.call("GET", "/api/plugins/" + type, null).status())
        .as("plugin %s is part of this build", type)
        .isEqualTo(200);
  }

  /** Runs {@code in -> step -> end} and returns what the run's results hold. */
  private static String run(
      final Api api,
      final Scenarios scenarios,
      final String type,
      final Map<String, Object> config) {
    final String session = Scenarios.unique("mcp");
    final Map<String, Object> workflow =
        Map.of(
            "workflowId",
            "w",
            "description",
            "d",
            "nodes",
            List.of(
                Map.of("nodeId", "in", "type", "API_TRIGGER", "config", Map.of()),
                Map.of("nodeId", "step", "type", type, "config", config),
                Map.of("nodeId", "end", "type", "CONSOLE_TERMINAL", "config", Map.of())),
            "edges",
            List.of(
                Map.of("source", "in", "target", "step"),
                Map.of("source", "step", "target", "end")));
    scenarios.applySession(
        JSON.writeValueAsString(
            Map.of(
                "sessionId",
                session,
                "name",
                "mcp",
                "description",
                "d",
                "initiator",
                "e2e",
                "projectPath",
                "/tmp",
                "workflows",
                Map.of("w", workflow))));
    final String execution =
        scenarios.startWorkflow(session, "w", Map.of("key", YuktaServer.key(ApiRole.ADMIN)));
    assertThat(scenarios.awaitTerminal(execution, Duration.ofSeconds(30)).path("status").asString())
        .isEqualTo("SUCCESS");
    return api.call(
            "GET", "/api/sessions/" + session + "/executions/" + execution + "/results", null)
        .body();
  }

  /**
   * The endpoint of this very server, as the server itself reaches it (a container publishes its
   * port elsewhere: {@code -Pyukta.e2e.self-url}); the plugin sends the key it is started with.
   */
  private static Map<String, Object> target(final Map<String, Object> extra) {
    final Map<String, Object> config = new ConcurrentHashMap<>(extra);
    config.put("url", System.getProperty("yukta.e2e.self-url", YuktaServer.baseUrl()) + "/mcp");
    // a definition may not hold a literal secret, so the key comes in with the start payload
    config.put("headers", Map.of("X-API-Key", "${payload.key}"));
    return config;
  }

  @Test
  void mcpListTools_listsTheServersTools(final Api api, final Scenarios scenarios) {
    requirePlugin(api, "MCP_LIST_TOOLS");

    assertThat(run(api, scenarios, "MCP_LIST_TOOLS", target(Map.of())))
        .contains("start_workflow", "list_sessions");
  }

  @Test
  void mcpToolCall_callsTool(final Api api, final Scenarios scenarios) {
    requirePlugin(api, "MCP_TOOL_CALL");

    final String out = run(api, scenarios, "MCP_TOOL_CALL", target(Map.of("tool", "list_plugins")));

    assertThat(out).contains("\"success\":true", "CONSOLE_TERMINAL");
  }
}
