// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Runs the stall warning and a concurrency group through the real application. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"yukta.process.allow-any=true", "yukta.concurrency.groups.tiny=2"})
@NoArgsConstructor
class EngineLimitsAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;
  @Autowired private ExecutionEventFeed feed;

  private static Node sleeper(final String name, final Map<String, Object> engine) {
    return new Node(
        name,
        "PROCESS_EXECUTOR",
        Map.of("command", List.of("sleep", "1"), "outputFormat", "RAW", "engine", engine));
  }

  private void define(final String session, final List<Node> steps) {
    final List<Node> nodes = new java.util.ArrayList<>();
    nodes.add(new Node("t", "CONSTANT_SOURCE", Map.of()));
    nodes.addAll(steps);
    nodes.add(new Node("end", "CONSOLE_TERMINAL", Map.of()));
    final List<Edge> edges = new java.util.ArrayList<>();
    for (final Node step : steps) {
      edges.add(new Edge("t", step.nodeId(), null));
      edges.add(new Edge(step.nodeId(), "end", null));
    }
    definitions
        .save(session, new WorkflowDefinition("wf", "engine limits acceptance", nodes, edges))
        .block();
  }

  private static boolean unfinished(final WorkflowProgress progress) {
    return progress == null || !progress.status().isTerminal();
  }

  @Test
  void silentNode_warnsOnceThatItStalled_andStillFinishes() {
    final String session = "stall-" + UUID.randomUUID();
    define(session, List.of(sleeper("slow", Map.of("stallAfterMs", 300))));
    final List<ExecutionEvent.LogLine> warnings = new CopyOnWriteArrayList<>();
    final var subscription =
        feed.events()
            .ofType(ExecutionEvent.LogLine.class)
            .filter(line -> line.line().startsWith("NodeStalled:"))
            .subscribe(warnings::add);

    final String executionId = gateway.startWorkflow(session, "wf").block();
    await().atMost(WAIT).until(() -> !unfinished(tracker.getProgressByExecutionId(executionId)));
    subscription.dispose();

    assertThat(tracker.getProgressByExecutionId(executionId).status())
        .isEqualTo(TaskStatus.SUCCESS);
    assertThat(warnings)
        .singleElement()
        .satisfies(
            warning -> {
              assertThat(warning.executionId()).isEqualTo(executionId);
              assertThat(warning.nodeId()).isEqualTo("slow");
              assertThat(warning.level().name()).isEqualTo("WARN");
            });
  }

  @Test
  void threeNodesInGroupOfTwo_neverRunMoreThanTwoAtOnce() {
    final String session = "group-" + UUID.randomUUID();
    final Map<String, Object> engine = Map.of("concurrencyGroup", "tiny");
    define(session, List.of(sleeper("a", engine), sleeper("b", engine), sleeper("c", engine)));

    final String executionId = gateway.startWorkflow(session, "wf").block();
    int mostRunning = 0;
    boolean sawQueued = false;
    final long deadline = System.nanoTime() + WAIT.toNanos();
    WorkflowProgress progress;
    do {
      progress = tracker.getProgressByExecutionId(executionId);
      if (progress != null) {
        final List<TaskStatus> states =
            progress.tasks().stream()
                .filter(task -> List.of("a", "b", "c").contains(task.nodeId()))
                .map(TaskProgress::status)
                .toList();
        mostRunning =
            Math.max(mostRunning, (int) states.stream().filter(TaskStatus.RUNNING::equals).count());
        sawQueued |= states.contains(TaskStatus.QUEUED);
      }
      await().pollDelay(Duration.ofMillis(20)).until(() -> true);
    } while (unfinished(progress) && System.nanoTime() < deadline);

    assertThat(progress.status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(mostRunning).isEqualTo(2);
    assertThat(sawQueued).isTrue();
  }
}
