// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static com.infenia.yukta.plugins.PluginWorkflows.node;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Happy path of each trigger and terminal, run as a real workflow in the real application. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.ShortVariable"})
@NoArgsConstructor
class TriggerTerminalAcceptanceTest {

  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private SubWorkflowGateway runner;
  @Autowired private PluginRegistry registry;

  private PluginWorkflows wf;

  @BeforeEach
  void setUp() {
    wf = new PluginWorkflows(definitions, runner, registry);
  }

  @Test
  void apiTrigger_andConsoleTerminal_carryTheRequestPayloadToTheEnd() {
    final var key =
        wf.save(
            List.of(node("in", "API_TRIGGER", Map.of()), node("end", "CONSOLE_TERMINAL", Map.of())),
            List.of(new Edge("in", "end", null)));

    assertThat(wf.runOne(key, Map.of("hello", "world"))).containsEntry("hello", "world");
  }

  @Test
  void constantSource_emitsItsVariables_withDottedKeysNested() {
    final var key =
        wf.save(
            List.of(
                node("src", "CONSTANT_SOURCE", Map.of("variables", Map.of("a", 1, "x.y", "deep"))),
                node("end", "CONSOLE_TERMINAL", Map.of())),
            List.of(new Edge("src", "end", null)));

    final Map<String, Object> out = wf.runOne(key, Map.of());

    assertThat(out).containsEntry("a", 1);
    assertThat(out.get("x")).isEqualTo(Map.of("y", "deep"));
  }

  @Test
  void scheduleTrigger_firesUntilMaxRuns() {
    final var key =
        wf.save(
            List.of(
                node("tick", "SCHEDULE_TRIGGER", Map.of("intervalMs", 50, "maxRuns", 2)),
                node("end", "CONSOLE_TERMINAL", Map.of())),
            List.of(new Edge("tick", "end", null)));

    final List<Object> out = wf.run(key, Map.of());

    assertThat(out).hasSize(2);
    assertThat(out.toString()).contains("tick");
  }

  @Test
  void notifyTerminal_postsTheMessageToTheWebhook() throws IOException {
    try (FakeHttp hook = new FakeHttp()) {
      final var key =
          wf.save(
              List.of(
                  node("in", "API_TRIGGER", Map.of()),
                  node(
                      "end",
                      "NOTIFY_TERMINAL",
                      Map.of("url", hook.url() + "/hook", "text", "deployed"))),
              List.of(new Edge("in", "end", null)));

      wf.run(key, Map.of());

      await().atMost(PluginWorkflows.WAIT).until(() -> !hook.requests().isEmpty());
      assertThat(hook.requests().get(0).method()).isEqualTo("POST");
      assertThat(hook.requests().get(0).body()).contains("deployed");
    }
  }
}
