// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static com.infenia.yukta.plugins.PluginWorkflows.node;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Happy path of each internal-core processor, run as a real workflow in the real application. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ShortVariable"
})
@NoArgsConstructor
class CorePluginsAcceptanceTest {

  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private SubWorkflowGateway runner;
  @Autowired private PluginRegistry registry;
  @Autowired private ControlBusGateway gateway;
  @Autowired private TaskTrackerService tracker;

  private PluginWorkflows wf;

  @BeforeEach
  void setUp() {
    wf = new PluginWorkflows(definitions, runner, registry);
  }

  @Test
  void filter_letsMatchingMessagesThrough() {
    final var key = wf.chain(node("f", "FILTER", Map.of("condition", "payload.status == 'ok'")));

    assertThat(wf.run(key, Map.of("status", "ok"))).hasSize(1);
    assertThat(wf.run(key, Map.of("status", "bad"))).isEmpty();
  }

  @Test
  void validate_passesValidPayload() {
    final var key =
        wf.chain(
            node(
                "v",
                "VALIDATE",
                Map.of("required", List.of("name"), "types", Map.of("age", "number"))));

    assertThat(wf.runOne(key, Map.of("name", "a", "age", 3))).containsEntry("name", "a");
  }

  @Test
  void mapper_projectsPayloadFields() {
    final var key =
        wf.chain(
            node("m", "MAPPER", Map.of("mode", "PROJECTION", "mapping", Map.of("y", "payload.x"))));

    assertThat(wf.runOne(key, Map.of("x", 7))).containsEntry("y", 7);
  }

  @Test
  void contentFilter_keepsOnlyTheIncludedPaths() {
    final var key =
        wf.chain(
            node("c", "CONTENT_FILTER", Map.of("mode", "INCLUDE", "paths", List.of("user.name"))));

    final Map<String, Object> out =
        wf.runOne(key, Map.of("user", Map.of("name", "john", "age", 30)));

    assertThat(out.toString()).contains("john").doesNotContain("30");
  }

  @Test
  void branch_routesToTheMatchingPort() {
    final var key =
        wf.save(
            List.of(
                node("in", "API_TRIGGER", Map.of()),
                node(
                    "b",
                    "BRANCH",
                    Map.of(
                        "mode",
                        "SELECT_KEY",
                        "selector",
                        "payload.kind",
                        "cases",
                        Map.of("a", "portA", "b", "portB"))),
                node("endA", "CONSOLE_TERMINAL", Map.of()),
                node("endB", "CONSOLE_TERMINAL", Map.of())),
            List.of(
                new Edge("in", "b", null),
                new Edge("b", "endA", "portA"),
                new Edge("b", "endB", "portB")));

    assertThat(wf.run(key, Map.of("kind", "a"))).hasSize(1);
  }

  @Test
  void splitter_thenAggregate_putsTheItemsBackTogether() {
    final var key =
        wf.chain(
            node("s", "SPLITTER", Map.of("itemsPath", "payload.items")),
            node("a", "AGGREGATE", Map.of("size", 3)));

    final Map<String, Object> out = wf.runOne(key, Map.of("items", List.of(1, 2, 3)));

    assertThat(out).containsEntry("count", 3);
    assertThat((List<?>) out.get("items")).hasSize(3);
  }

  @Test
  void recipientList_sendsCopyToEveryPort() {
    final var key =
        wf.save(
            List.of(
                node("in", "API_TRIGGER", Map.of()),
                node(
                    "r",
                    "RECIPIENT_LIST",
                    Map.of("mode", "STATIC", "recipients", List.of("p1", "p2"))),
                node("e1", "CONSOLE_TERMINAL", Map.of()),
                node("e2", "CONSOLE_TERMINAL", Map.of())),
            List.of(
                new Edge("in", "r", null), new Edge("r", "e1", "p1"), new Edge("r", "e2", "p2")));

    assertThat(wf.run(key, Map.of("x", 1))).hasSize(2);
  }

  @Test
  void delay_passesTheMessageOnAfterTheDelay() {
    final var key = wf.chain(node("d", "DELAY", Map.of("delayMs", 200)));

    final long start = System.nanoTime();
    final Map<String, Object> out = wf.runOne(key, Map.of("x", 1));

    assertThat(out).containsEntry("x", 1);
    assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(200);
  }

  @Test
  void kvStore_putThenGet_returnsTheStoredValue() {
    final var key =
        wf.chain(
            node("put", "KV_STORE", Map.of("op", "put", "key", "k1", "namespace", "it")),
            node("get", "KV_STORE", Map.of("op", "get", "key", "k1", "namespace", "it")));

    assertThat(wf.runOne(key, Map.of("value", "stored")).toString()).contains("stored");
  }

  @Test
  void subWorkflow_runsTheChildAndReturnsItsResults() {
    final String session = "it-sub";
    final var child =
        wf.chainIn(
            session,
            node("m", "MAPPER", Map.of("mode", "PROJECTION", "mapping", Map.of("y", "'child'"))));
    final var parent =
        wf.chainIn(session, node("sub", "SUB_WORKFLOW", Map.of("workflowId", child.workflowId())));

    assertThat(wf.runOne(parent, Map.of()).toString()).contains("child");
  }

  private static Map<String, Object> increment() {
    return Map.of("mode", "PROJECTION", "mapping", Map.of("n", "payload.n + 1"));
  }

  @Test
  void loopPredicate_repeatsUntilTheExitConditionHolds() {
    final var key =
        wf.chain(
            node(
                "l",
                "LOOP_PREDICATE",
                Map.of(
                    "targetPluginId",
                    "MAPPER",
                    "targetConfig",
                    increment(),
                    "exitCondition",
                    "#root.payload['n'] >= 3")));

    assertThat(wf.runOne(key, Map.of("n", 0))).containsEntry("n", 3);
  }

  @Test
  void loopStream_emitsEveryIteration() {
    final var key =
        wf.chain(
            node(
                "l",
                "LOOP_STREAM",
                Map.of(
                    "targetPluginId",
                    "MAPPER",
                    "targetConfig",
                    increment(),
                    "maxIterations",
                    2,
                    "exitCondition",
                    "false")));

    assertThat(wf.run(key, Map.of("n", 0))).hasSize(2);
  }

  @Test
  void waitForSignal_holdsTheRunUntilItIsApproved() {
    final var key = wf.chain(node("w", "WAIT_FOR_SIGNAL", Map.of("signal", "approve")));
    final String executionId = gateway.startWorkflow(key.session(), key.workflowId()).block();
    await()
        .atMost(PluginWorkflows.WAIT)
        .until(
            () ->
                tracker.getProgressByExecutionId(executionId).tasks().stream()
                    .anyMatch(t -> "w".equals(t.nodeId()) && "RUNNING".equals(t.status())));
    assertThat(tracker.getProgressByExecutionId(executionId).status()).isEqualTo("RUNNING");

    gateway.signalNode(executionId, "w", "approve", "approve", "go").block(PluginWorkflows.WAIT);

    await()
        .atMost(PluginWorkflows.WAIT)
        .until(() -> "SUCCESS".equals(tracker.getProgressByExecutionId(executionId).status()));
  }
}
