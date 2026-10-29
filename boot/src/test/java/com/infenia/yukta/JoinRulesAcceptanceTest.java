// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
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

/** A join that carries on after a failed branch, through the real application. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@NoArgsConstructor
class JoinRulesAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private SubWorkflowGateway subWorkflows;

  private static Node shell(final String nodeId, final String script) {
    return new Node(
        nodeId,
        "PROCESS_EXECUTOR",
        Map.of("command", List.of("sh", "-c", script), "outputFormat", "RAW"));
  }

  /** Defines a workflow whose "bad" branch (exit 3) fails before an ALL_DONE join with "good". */
  private void defineFanOut(final String session) {
    definitions
        .save(
            session,
            new WorkflowDefinition(
                "fan-out",
                "one branch fails behind an ALL_DONE join",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    shell("good", "echo good"),
                    shell("bad", "echo bad; exit 3"),
                    new Node(
                        "join",
                        "AGGREGATE",
                        Map.of("size", 2, "engine", Map.of("trigger", "ALL_DONE"))),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(
                    new Edge("t", "good", null),
                    new Edge("t", "bad", null),
                    new Edge("good", "join", null),
                    new Edge("bad", "join", null),
                    new Edge("join", "end", null))))
        .block();
  }

  private static String statusOf(final WorkflowProgress progress, final String nodeId) {
    return progress.tasks().stream()
        .filter(task -> task.nodeId().equals(nodeId))
        .map(TaskProgress::status)
        .findFirst()
        .orElse("missing");
  }

  @Test
  void allDoneJoin_carriesOnWithTheBranchesThatWorked_andTheFailedBranchStaysVisible() {
    final String session = "join-" + UUID.randomUUID();
    defineFanOut(session);

    final String executionId = gateway.startWorkflow(session, "fan-out").block();
    await()
        .atMost(WAIT)
        .until(
            () -> {
              final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
              return progress != null
                  && !List.of("PENDING", "QUEUED", "RUNNING").contains(progress.status());
            });
    final WorkflowProgress run = tracker.getProgressByExecutionId(executionId);

    assertThat(statusOf(run, "bad")).isEqualTo("FAILURE");
    assertThat(statusOf(run, "good")).isEqualTo("SUCCESS");
    assertThat(statusOf(run, "join")).isEqualTo("SUCCESS");
    assertThat(statusOf(run, "end")).isEqualTo("SUCCESS");
    assertThat(run.status()).isEqualTo("SUCCESS");
  }

  @Test
  void allDoneJoin_namesTheBranchesThatDelivered() {
    final String session = "join-" + UUID.randomUUID();
    defineFanOut(session);

    final SubWorkflowGateway.Result result =
        subWorkflows.run(session, "fan-out", Map.of(), "test", 1).block(WAIT);

    assertThat(result).isNotNull();
    assertThat(result.results())
        .singleElement()
        .satisfies(
            batch -> assertThat(((Map<?, ?>) batch).get("sources")).isEqualTo(List.of("good")));
  }
}
