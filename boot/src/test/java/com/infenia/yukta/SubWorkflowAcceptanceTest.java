// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Runs sub-workflows through the real application: results, failure and the nesting limit. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class SubWorkflowAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private SubWorkflowGateway subWorkflows;
  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private ExecutionLogStore executionLog;

  private void define(final String session, final String workflowId, final Node step) {
    definitions
        .save(
            session,
            new WorkflowDefinition(
                workflowId,
                "sub-workflow acceptance",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    step,
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", step.nodeId(), null), new Edge(step.nodeId(), "end", null))))
        .block();
  }

  private static Node echo(final String text) {
    return new Node(
        "p", "PROCESS_EXECUTOR", Map.of("command", List.of("echo", text), "outputFormat", "RAW"));
  }

  private WorkflowProgress awaitTerminal(final String executionId) {
    await()
        .atMost(WAIT)
        .until(
            () -> {
              final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
              return progress != null
                  && !List.of("PENDING", "QUEUED", "RUNNING").contains(progress.status());
            });
    return tracker.getProgressByExecutionId(executionId);
  }

  @Test
  void childResults_reachTheCaller_andTheChildRecordsItsParent() {
    final String session = "sub-" + UUID.randomUUID();
    define(session, "child", echo("from-child"));

    final SubWorkflowGateway.Result result =
        subWorkflows.run(session, "child", Map.of(), "parent-run", 1).block(WAIT);

    assertThat(result).isNotNull();
    assertThat(result.results()).isNotEmpty();
    assertThat(String.valueOf(result.results())).contains("from-child");
    assertThat(executionLog.readAll())
        .filteredOn(
            event ->
                event.executionId().equals(result.executionId())
                    && event.type() == ExecutionLogEvent.Type.RUN_STARTED)
        .singleElement()
        .satisfies(
            started ->
                assertThat(started.payload())
                    .containsEntry(SubWorkflowGateway.PARENT_KEY, "parent-run")
                    .containsEntry(SubWorkflowGateway.DEPTH_KEY, 1));
  }

  @Test
  void parentWithSubWorkflowNode_succeeds_andOneFailingChildFailsTheParent() {
    final String session = "sub-" + UUID.randomUUID();
    define(session, "child", echo("ok"));
    define(
        session,
        "bad-child",
        new Node("p", "PROCESS_EXECUTOR", Map.of("command", List.of("sh", "-c", "exit 3"))));
    define(session, "parent", new Node("sub", "SUB_WORKFLOW", Map.of("workflowId", "child")));
    define(
        session, "bad-parent", new Node("sub", "SUB_WORKFLOW", Map.of("workflowId", "bad-child")));

    final WorkflowProgress good = awaitTerminal(gateway.startWorkflow(session, "parent").block());
    final WorkflowProgress bad =
        awaitTerminal(gateway.startWorkflow(session, "bad-parent").block());

    assertThat(good.status()).isEqualTo("SUCCESS");
    assertThat(bad.status()).isEqualTo("FAILURE");
  }

  /** A parent whose SUB_WORKFLOW routes on how the child ended, into one terminal per port. */
  private void defineRoutingParent(final String session, final String workflowId) {
    final List<String> ports = List.of("success", "failure", "retry", "escalate");
    final List<Node> nodes = new java.util.ArrayList<>();
    final List<Edge> edges = new java.util.ArrayList<>();
    nodes.add(new Node("t", "CONSTANT_SOURCE", Map.of()));
    nodes.add(
        new Node("sub", "SUB_WORKFLOW", Map.of("workflowId", workflowId, "routeByOutcome", true)));
    edges.add(new Edge("t", "sub", null));
    for (final String port : ports) {
      nodes.add(new Node("on-" + port, "CONSOLE_TERMINAL", Map.of()));
      edges.add(new Edge("sub", "on-" + port, port));
    }
    definitions
        .save(session, new WorkflowDefinition("parent-of-" + workflowId, "routes", nodes, edges))
        .block();
  }

  private static String statusOf(final WorkflowProgress progress, final String nodeId) {
    return progress.tasks().stream()
        .filter(task -> task.nodeId().equals(nodeId))
        .map(task -> task.status())
        .findFirst()
        .orElse("missing");
  }

  @Test
  void routeByOutcome_takesThePortOfTheOutcomeTheChildReached_orFailureWhenItFailed() {
    final String session = "sub-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                "escalating",
                "ends in ESCALATE",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    new Node(
                        "out",
                        "OUTCOME_TERMINAL",
                        Map.of("outcome", "ESCALATE", "reason", "needs a human"))),
                List.of(new Edge("t", "out", null))))
        .block();
    define(
        session,
        "failing",
        new Node("p", "PROCESS_EXECUTOR", Map.of("command", List.of("sh", "-c", "exit 3"))));
    defineRoutingParent(session, "escalating");
    defineRoutingParent(session, "failing");

    final WorkflowProgress escalated =
        awaitTerminal(gateway.startWorkflow(session, "parent-of-escalating").block());
    final WorkflowProgress failed =
        awaitTerminal(gateway.startWorkflow(session, "parent-of-failing").block());

    assertThat(escalated.status()).isEqualTo("SUCCESS");
    assertThat(statusOf(escalated, "on-escalate")).isEqualTo("SUCCESS");
    assertThat(statusOf(escalated, "on-success")).isNotEqualTo("SUCCESS");
    assertThat(failed.status()).isEqualTo("SUCCESS");
    assertThat(statusOf(failed, "on-failure")).isEqualTo("SUCCESS");
    assertThat(statusOf(failed, "on-success")).isNotEqualTo("SUCCESS");
  }

  @Test
  void unknownChildAndTooDeepNesting_areRefused() {
    final String session = "sub-" + UUID.randomUUID();

    assertThatThrownBy(() -> subWorkflows.run(session, "nope", Map.of(), "p", 1).block(WAIT))
        .hasMessageContaining("Workflow not found");
    assertThatThrownBy(
            () ->
                subWorkflows
                    .run(session, "nope", Map.of(), "p", SubWorkflowGateway.MAX_DEPTH + 1)
                    .block(WAIT))
        .hasMessageContaining("nested more than");
  }
}
