// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static com.infenia.yukta.plugins.PluginWorkflows.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Happy path of the MCP client plugins, against Yukta's own MCP endpoint on a real port. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({"PMD.CommentRequired", "PMD.ShortVariable"})
@NoArgsConstructor
class McpPluginsAcceptanceTest {

  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private SubWorkflowGateway runner;
  @Autowired private PluginRegistry registry;
  @LocalServerPort private int port;

  private PluginWorkflows wf;

  @BeforeEach
  void setUp() {
    wf = new PluginWorkflows(definitions, runner, registry);
  }

  private String mcpUrl() {
    return "http://127.0.0.1:" + port + "/mcp";
  }

  @Test
  void mcpListTools_listsTheServersTools() {
    wf.requirePlugin("MCP_LIST_TOOLS");
    final var key = wf.chain(node("l", "MCP_LIST_TOOLS", Map.of("url", mcpUrl())));

    final Map<String, Object> out = wf.runOne(key, Map.of());

    assertThat(out.toString()).contains("start_workflow", "list_sessions");
  }

  @Test
  void mcpToolCall_callsTool() {
    wf.requirePlugin("MCP_TOOL_CALL");
    final var key =
        wf.chain(node("c", "MCP_TOOL_CALL", Map.of("url", mcpUrl(), "tool", "list_plugins")));

    final Map<String, Object> out = wf.runOne(key, Map.of());

    assertThat(out).containsEntry("success", true);
    assertThat(out.toString()).contains("CONSOLE_TERMINAL");
  }
}
