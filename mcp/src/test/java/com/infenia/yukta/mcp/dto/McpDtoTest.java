// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpDtoTest {

  @Test
  void testControlActionResultNullListDefaultsToEmpty() {
    assertThat(new ControlActionResult("PAUSE", "e1", null, null, "ok").resultExecutionIds())
        .isEmpty();
    assertThat(
            new ControlActionResult("PAUSE", "e1", null, List.of("e2"), "ok").resultExecutionIds())
        .containsExactly("e2");
  }

  @Test
  void testExecutionLogsNullLinesDefaultsToEmpty() {
    assertThat(new ExecutionLogs("e1", 0, 0, null).lines()).isEmpty();
    assertThat(new ExecutionLogs("e1", 1, 1, List.of("l1")).lines()).containsExactly("l1");
  }

  @Test
  void testSessionDetailsNullWorkflowsDefaultsToEmpty() {
    assertThat(new SessionDetails("s1", null).workflowIds()).isEmpty();
    assertThat(new SessionDetails("s1", List.of("wf")).workflowIds()).containsExactly("wf");
  }

  @Test
  void testSessionCreationResultNullListsDefaultToEmpty() {
    final SessionCreationResult result = new SessionCreationResult("s1", null, null, true);
    assertThat(result.createdWorkflows()).isEmpty();
    assertThat(result.warnings()).isEmpty();
    final SessionCreationResult filled =
        new SessionCreationResult("s1", List.of("wf"), List.of("warn"), false);
    assertThat(filled.createdWorkflows()).containsExactly("wf");
    assertThat(filled.warnings()).containsExactly("warn");
  }

  @Test
  void testSessionCreationGuideNullListsDefaultToEmpty() {
    final SessionCreationGuide guide = new SessionCreationGuide("a", "b", "c", "d", null, null);
    assertThat(guide.availablePlugins()).isEmpty();
    assertThat(guide.commonErrors()).isEmpty();
    final SessionCreationGuide filled =
        new SessionCreationGuide(
            "a",
            "b",
            "c",
            "d",
            List.of(new PluginReference("t", "PROCESSOR", "d")),
            List.of(new ErrorExample("e", "c", "r")));
    assertThat(filled.availablePlugins()).hasSize(1);
    assertThat(filled.commonErrors()).hasSize(1);
  }

  @Test
  void testPluginDetailsNullPortsDefaultsToEmpty() {
    assertThat(new PluginDetails("t", null, "d", "u", null, null).outputPorts()).isEmpty();
    assertThat(new PluginDetails("t", null, "d", "u", null, List.of("out")).outputPorts())
        .containsExactly("out");
  }

  @Test
  void testPluginCreationGuideNullTemplatesDefaultsToEmpty() {
    assertThat(new PluginCreationGuide("a", null, "i", "c", "v", "t", "d").templateCode())
        .isEmpty();
    assertThat(
            new PluginCreationGuide("a", Map.of("k", "v"), "i", "c", "v", "t", "d").templateCode())
        .containsEntry("k", "v");
  }

  @Test
  void testControlBusStatusNullListsDefaultToEmpty() {
    final ControlBusStatus status = new ControlBusStatus(null, null, null, null);
    assertThat(status.activeSessions()).isEmpty();
    assertThat(status.pluginRegistry()).isEmpty();
    assertThat(status.recentExecutions()).isEmpty();
    final ControlBusStatus filled =
        new ControlBusStatus(
            List.of(new SessionExecutionInfo("s1", 1, 2)),
            List.of(new PluginRegistryEntry("t", "PROCESSOR", "ACTIVE")),
            new SystemHealthMetrics(1.0, 0, "1 MB", "2 MB", "1s"),
            List.of(new ExecutionRecord("s1", "e1", "RUNNING", "running")));
    assertThat(filled.activeSessions()).hasSize(1);
    assertThat(filled.pluginRegistry()).hasSize(1);
    assertThat(filled.recentExecutions()).hasSize(1);
  }

  @Test
  void testListWrappersNullListsDefaultToEmpty() {
    assertThat(new SessionList(null).sessions()).isEmpty();
    assertThat(new SessionList(List.of(new SessionSummary("s1", 1))).sessions()).hasSize(1);
    assertThat(new WorkflowHistory(null).executions()).isEmpty();
    assertThat(new WorkflowHistory(List.of()).executions()).isEmpty();
    assertThat(new PluginList(null).plugins()).isEmpty();
    assertThat(new PluginList(List.of(new PluginSummary("t", null))).plugins()).hasSize(1);
  }

  @Test
  void testListsAreImmutable() {
    final ExecutionLogs logs = new ExecutionLogs("e1", 1, 1, List.of("l1"));
    assertThatThrownBy(() -> logs.lines().add("l2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
