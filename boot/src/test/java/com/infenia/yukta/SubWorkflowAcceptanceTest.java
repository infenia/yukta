// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.control.TreeControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
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
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
@NoArgsConstructor
@ExpectedErrorLog({
  "ERROR com.infenia.yukta.plugin.process.ProcessExecutorPlugin - ",
  "ERROR com.infenia.yukta.service.orchestrator.stream.NodeStreams - ",
  "ERROR com.infenia.yukta.service.orchestrator.strategy.StreamAssemblyHelper - ",
  "ERROR com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator - ",
  "ERROR com.infenia.yukta.service.orchestrator.WorkflowOrchestrator - "
})
class SubWorkflowAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private SubWorkflowGateway subWorkflows;
  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private ExecutionLogStore executionLog;
  @Autowired private TreeControl treeControl;

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
              return progress != null && progress.status().isTerminal();
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
    final WorkflowProgress child = tracker.getProgressByExecutionId(result.executionId());
    assertThat(child.parentExecutionId()).isEqualTo("parent-run");
    assertThat(child.rootExecutionId()).isEqualTo("parent-run");
    assertThat(tracker.isWithin(result.executionId(), "parent-run")).isTrue();
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

    assertThat(good.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(bad.status()).isEqualTo(TaskStatus.FAILURE);
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

  private static TaskStatus statusOf(final WorkflowProgress progress, final String nodeId) {
    return progress.tasks().stream()
        .filter(task -> task.nodeId().equals(nodeId))
        .map(TaskProgress::status)
        .findFirst()
        .orElse(null);
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

    assertThat(escalated.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(statusOf(escalated, "on-escalate")).isEqualTo(TaskStatus.SUCCESS);
    assertThat(statusOf(escalated, "on-success")).isNotEqualTo(TaskStatus.SUCCESS);
    assertThat(failed.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(statusOf(failed, "on-failure")).isEqualTo(TaskStatus.SUCCESS);
    assertThat(statusOf(failed, "on-success")).isNotEqualTo(TaskStatus.SUCCESS);
  }

  @Test
  void startVariables_reachEveryNodeOfTheChild_fromTheSubWorkflowPayload() {
    final String session = "sub-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                "child",
                "reads its start payload two nodes in",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    new Node(
                        "first",
                        "PROCESS_EXECUTOR",
                        Map.of("command", List.of("echo", "first"), "outputFormat", "RAW")),
                    new Node(
                        "second",
                        "PROCESS_EXECUTOR",
                        Map.of(
                            "command",
                            List.of("echo", "project=${start.project}"),
                            "outputFormat",
                            "RAW")),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(
                    new Edge("t", "first", null),
                    new Edge("first", "second", null),
                    new Edge("second", "end", null))))
        .block();

    final SubWorkflowGateway.Result result =
        subWorkflows
            .run(session, "child", Map.of("project", "/work/x"), "parent-run", 1)
            .block(WAIT);

    assertThat(result).isNotNull();
    assertThat(String.valueOf(result.results())).contains("project=/work/x");

    // a parent hands its own start payload on: SUB_WORKFLOW payload values are templated
    definitions
        .save(
            session,
            new WorkflowDefinition(
                "parent",
                "passes its start payload on",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    new Node(
                        "sub",
                        "SUB_WORKFLOW",
                        Map.of(
                            "workflowId", "child", "payload", Map.of("project", "${start.root}"))),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", "sub", null), new Edge("sub", "end", null))))
        .block();
    final WorkflowProgress parent =
        awaitTerminal(
            gateway.startWorkflow(session, "parent", Map.of("root", "/from/parent")).block());
    assertThat(parent.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(executionLog.readAll())
        .filteredOn(
            event ->
                event.type() == ExecutionLogEvent.Type.RUN_STARTED
                    && "/from/parent"
                        .equals(
                            java.util.Objects.requireNonNullElse(event.payload(), Map.of())
                                .get("project")))
        .isNotEmpty();
  }

  @Test
  void pausingTheTree_pausesTheChildTheParentWaitsOn_andResumingTheTreeLetsBothFinish() {
    final String session = "sub-" + UUID.randomUUID();
    define(
        session,
        "slow-child",
        new Node("p", "PROCESS_EXECUTOR", Map.of("command", List.of("sleep", "3"))));
    define(
        session,
        "waiting-parent",
        new Node("sub", "SUB_WORKFLOW", Map.of("workflowId", "slow-child", "timeoutSeconds", 60)));

    final String parent = gateway.startWorkflow(session, "waiting-parent").block();
    await().atMost(WAIT).until(() -> tracker.getTree(parent).size() == 2);
    final String child = tracker.getTree(parent).get(1).executionId();

    treeControl.apply(parent, true, gateway::pauseWorkflow).block(WAIT);
    await()
        .atMost(WAIT)
        .until(() -> "PAUSED".equals(tracker.getProgressByExecutionId(child).controlState()));
    assertThat(tracker.getProgressByExecutionId(parent).controlState()).isEqualTo("PAUSED");

    treeControl.apply(parent, true, gateway::resumeWorkflow).block(WAIT);
    assertThat(awaitTerminal(parent).status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(tracker.getProgressByExecutionId(child).status()).isEqualTo(TaskStatus.SUCCESS);
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
