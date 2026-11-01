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
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Properties that must hold for every valid workflow, checked on generated DAGs run by the real
 * application (real plugins, tracker, scheduler and control bus). Node behaviour is simulated with
 * PROCESS_EXECUTOR (success, {@code exit 1}, {@code sleep}), so nothing leaves the machine.
 *
 * <p>Cases come from a seeded {@link Random}, smallest first, so the first failure is also a small
 * one. A failure prints its seed and the DAG; replay it with {@code -Dyukta.dag.seed=<seed>
 * -Dyukta.dag.tries=1}. The nightly run raises the count with {@code -PdagTries=...} and picks a
 * random base seed with {@code -PdagSeed=random}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.CognitiveComplexity",
  "PMD.CyclomaticComplexity",
  "PMD.NPathComplexity",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidLiteralsInIfCondition"
})
@NoArgsConstructor
@ExpectedErrorLog(
    sometimes = {
      "ERROR com.infenia.yukta.service.orchestrator.validator.WorkflowValidator - ",
      "ERROR com.infenia.yukta.service.orchestrator.preparator.WorkflowPreparator - ",
      "UpstreamFailure: ",
      "WorkflowExecutionException: ",
      "dropped next: ProcessExecutionResult",
      "dropped error: java.util.concurrent.CancellationException: Disconnected"
    })
class DagPropertyAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(30);
  private static final Set<TaskStatus> RUNNING =
      EnumSet.of(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING);

  @Autowired private ControlBusGateway gateway;
  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private TaskTrackerService tracker;

  /** A generated workflow and the facts the properties check it against. */
  private record Dag(
      long seed, List<Node> nodes, List<Edge> edges, Set<String> failing, Set<String> slow) {

    @Override
    public String toString() {
      final String arrows =
          edges.stream()
              .map(edge -> edge.source() + "->" + edge.target())
              .collect(java.util.stream.Collectors.joining(" "));
      return "seed=" + seed + " edges: " + arrows + " failing=" + failing + " slow=" + slow;
    }
  }

  private static Node process(final String id, final List<String> command) {
    return new Node(id, "PROCESS_EXECUTOR", Map.of("command", command, "outputFormat", "RAW"));
  }

  /**
   * A DAG of {@code size} processors under one trigger: every processor has one or two parents
   * among the earlier ones, every sink feeds a terminal, so it is valid by construction.
   */
  private static Dag generate(
      final long seed, final int size, final boolean allowFailures, final boolean oneSlow) {
    final Random random = new Random(seed);
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    final Set<String> failing = new HashSet<>();
    final Set<String> slow = new HashSet<>();
    final Set<String> withChildren = new HashSet<>();
    nodes.add(new Node("t", "CONSTANT_SOURCE", Map.of()));
    final int slowIndex = random.nextInt(size);
    for (int i = 0; i < size; i++) {
      final String id = "n" + i;
      if (oneSlow && i == slowIndex) {
        slow.add(id);
        nodes.add(process(id, List.of("sleep", "30")));
      } else if (allowFailures && random.nextInt(4) == 0) {
        failing.add(id);
        nodes.add(process(id, List.of("sh", "-c", "exit 1")));
      } else {
        nodes.add(process(id, List.of("true")));
      }
      final Set<String> parents = new HashSet<>();
      parents.add(i == 0 ? "t" : random.nextBoolean() ? "t" : "n" + random.nextInt(i));
      if (i > 1 && random.nextInt(3) == 0) {
        parents.add("n" + random.nextInt(i));
      }
      parents.forEach(parent -> edges.add(new Edge(parent, id, null)));
      withChildren.addAll(parents);
    }
    final boolean oneTerminal = random.nextBoolean();
    if (oneTerminal) {
      nodes.add(new Node("end", "CONSOLE_TERMINAL", Map.of()));
    }
    for (int i = 0; i < size; i++) {
      final String id = "n" + i;
      if (!withChildren.contains(id)) {
        final String terminal = oneTerminal ? "end" : "end" + i;
        if (!oneTerminal) {
          nodes.add(new Node(terminal, "CONSOLE_TERMINAL", Map.of()));
        }
        edges.add(new Edge(id, terminal, null));
      }
    }
    return new Dag(seed, nodes, edges, failing, slow);
  }

  /** Whether a node hands data to its children. */
  private enum Delivers {
    YES,
    NO,
    MAYBE
  }

  /**
   * The statuses each node may end with, from what the engine guarantees.
   *
   * <ul>
   *   <li>A processor whose parents all deliver is SUCCESS, or FAILURE when its own command fails;
   *       it delivers only on SUCCESS.
   *   <li>A processor with no delivering parent is UPSTREAM_FAILED.
   *   <li>A processor with both kinds of parent depends on timing (it may run on the live input
   *       first), so either outcome is allowed.
   *   <li>A terminal is SUCCESS only when every parent delivers, UPSTREAM_FAILED when one does not.
   *   <li>The first terminal that fails ends the run and cancels the other branches, so any node
   *       may be CANCELLED, or UPSTREAM_FAILED behind a cancelled one, but only in a run that has a
   *       failure.
   * </ul>
   */
  private static Map<String, Set<String>> allowedStatuses(final Dag dag) {
    final Map<String, List<String>> parents = new HashMap<>();
    dag.edges()
        .forEach(
            edge ->
                parents.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge.source()));
    final Map<String, Delivers> delivers = new HashMap<>();
    final Map<String, Set<String>> allowed = new HashMap<>();
    // dag.nodes() lists a node after all its parents.
    for (final Node node : dag.nodes()) {
      final String id = node.nodeId();
      final List<Delivers> inputs =
          parents.getOrDefault(id, List.of()).stream().map(delivers::get).toList();
      final boolean allYes = inputs.stream().allMatch(d -> d == Delivers.YES);
      final boolean allNo = !inputs.isEmpty() && inputs.stream().allMatch(d -> d == Delivers.NO);
      final boolean anyNo = inputs.contains(Delivers.NO);
      final boolean failing = dag.failing().contains(id);
      final Set<String> statuses = new HashSet<>();
      if ("CONSTANT_SOURCE".equals(node.type())) {
        statuses.add("SUCCESS");
        delivers.put(id, Delivers.YES);
      } else if ("CONSOLE_TERMINAL".equals(node.type())) {
        if (allYes) {
          statuses.add("SUCCESS");
        } else if (anyNo) {
          statuses.add("UPSTREAM_FAILED");
        } else {
          statuses.addAll(Set.of("SUCCESS", "UPSTREAM_FAILED"));
        }
        delivers.put(id, Delivers.NO);
      } else if (allYes) {
        statuses.add(failing ? "FAILURE" : "SUCCESS");
        delivers.put(id, failing ? Delivers.NO : Delivers.YES);
      } else if (allNo) {
        statuses.add("UPSTREAM_FAILED");
        delivers.put(id, Delivers.NO);
      } else {
        statuses.addAll(Set.of(failing ? "FAILURE" : "SUCCESS", "UPSTREAM_FAILED"));
        delivers.put(id, failing ? Delivers.NO : Delivers.MAYBE);
      }
      if (!dag.failing().isEmpty()) {
        // Cancelling a branch cascades down it as UPSTREAM_FAILED (see the issue linked in the PR).
        statuses.addAll(Set.of("CANCELLED", "UPSTREAM_FAILED"));
      }
      allowed.put(id, statuses);
    }
    return allowed;
  }

  private String define(final Dag dag, final String session) {
    final String workflowId = "wf-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(workflowId, "dag-" + dag.seed(), dag.nodes(), dag.edges()))
        .block();
    return workflowId;
  }

  private WorkflowProgress awaitTerminal(final String executionId) {
    await()
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(10))
        .until(
            () -> {
              final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
              return progress != null && !RUNNING.contains(progress.status());
            });
    return tracker.getProgressByExecutionId(executionId);
  }

  private static int tries() {
    return Integer.getInteger("yukta.dag.tries", 40);
  }

  private static long baseSeed() {
    final String seed = System.getProperty("yukta.dag.seed", "20261024");
    return "random".equals(seed) ? System.nanoTime() : Long.parseLong(seed);
  }

  private static int sizeFor(final int attempt, final int total) {
    return 1 + attempt * 7 / Math.max(1, total);
  }

  @Test
  void everyValidWorkflow_endsWithTheStatusItsFailuresImply() {
    final long base = baseSeed();
    for (int attempt = 0; attempt < tries(); attempt++) {
      final Dag dag = generate(base + attempt, sizeFor(attempt, tries()), true, false);
      final String session = "dag-" + UUID.randomUUID();
      final String workflowId = define(dag, session);
      final String executionId = gateway.startWorkflow(session, workflowId).block();

      final WorkflowProgress progress = awaitTerminal(executionId);

      assertThat(progress.status())
          .as("run status of %s", dag)
          .isEqualTo(dag.failing().isEmpty() ? TaskStatus.SUCCESS : TaskStatus.FAILURE);
      final Map<String, Set<String>> expected = allowedStatuses(dag);
      final Map<String, TaskProgress> tasks = new HashMap<>();
      progress.tasks().forEach(task -> tasks.put(task.nodeId(), task));
      assertThat(tasks.keySet()).as("nodes reported for %s", dag).isEqualTo(expected.keySet());
      expected.forEach(
          (id, statuses) ->
              assertThat(tasks.get(id).status().name())
                  .as("status of %s in %s", id, dag)
                  .isIn(statuses.toArray()));
      // A failing run ends early (the first failing terminal cancels the rest), so order only holds
      // when the run completes normally.
      for (final Edge edge : dag.failing().isEmpty() ? dag.edges() : List.<Edge>of()) {
        final TaskProgress from = tasks.get(edge.source());
        final TaskProgress to = tasks.get(edge.target());
        if (from.endTime() != null && to.endTime() != null) {
          assertThat(to.endTime())
              .as("%s must not end before its upstream %s in %s", edge.target(), edge.source(), dag)
              .isAfterOrEqualTo(from.endTime());
        }
      }
    }
  }

  @Test
  void stoppingAnyRunningWorkflow_endsItStoppedWithNothingRunning() {
    final long base = baseSeed();
    final int total = Math.max(1, tries() / 4);
    for (int attempt = 0; attempt < total; attempt++) {
      final Dag dag = generate(base + attempt, sizeFor(attempt, total), false, true);
      final String session = "dag-" + UUID.randomUUID();
      final String workflowId = define(dag, session);
      final String executionId = gateway.startWorkflow(session, workflowId).block();
      final String slowNode = dag.slow().iterator().next();
      await()
          .atMost(WAIT)
          .pollInterval(Duration.ofMillis(10))
          .until(
              () ->
                  tracker.getProgressByExecutionId(executionId).tasks().stream()
                      .anyMatch(
                          task ->
                              slowNode.equals(task.nodeId())
                                  && TaskStatus.RUNNING == task.status()));

      gateway.stopWorkflow(session, workflowId, "property", StopMode.CANCEL).block();

      final WorkflowProgress progress = awaitTerminal(executionId);
      assertThat(progress.status()).as("run status of %s", dag).isEqualTo(TaskStatus.STOPPED);
      assertThat(progress.tasks())
          .as("nodes still running after the stop of %s", dag)
          .noneMatch(task -> RUNNING.contains(task.status()));
    }
  }

  @Test
  void invalidWorkflows_areRejectedWithAnIllegalArgument_neverRun() {
    final Dag valid = generate(baseSeed(), 3, false, false);
    final String sink = valid.edges().getLast().source();
    final List<Dag> broken = new ArrayList<>();
    broken.add(mutate(valid, nodes -> nodes.add(process("n0", List.of("true"))), edges -> {}));
    broken.add(
        mutate(
            valid,
            nodes -> {
              nodes.add(process("x", List.of("true")));
              nodes.add(process("y", List.of("true")));
            },
            edges -> {
              edges.add(new Edge("t", "x", null));
              edges.add(new Edge("x", "y", null));
              edges.add(new Edge("y", "x", null));
              edges.add(new Edge("y", sink, null));
            }));
    broken.add(mutate(valid, nodes -> {}, edges -> edges.add(new Edge("n0", "nowhere", null))));
    broken.add(mutate(valid, nodes -> nodes.add(process("island", List.of("true"))), edges -> {}));
    broken.add(
        mutate(
            valid,
            nodes -> nodes.removeIf(node -> "CONSOLE_TERMINAL".equals(node.type())),
            edges -> edges.removeIf(edge -> edge.target().startsWith("end"))));
    for (final Dag dag : broken) {
      final String session = "dag-" + UUID.randomUUID();
      assertThatThrownBy(
              () -> {
                final String workflowId = define(dag, session);
                gateway.startWorkflow(session, workflowId).block();
              })
          .as("a workflow with %s", dag)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static Dag mutate(
      final Dag dag,
      final java.util.function.Consumer<List<Node>> onNodes,
      final java.util.function.Consumer<List<Edge>> onEdges) {
    final List<Node> nodes = new ArrayList<>(dag.nodes());
    final List<Edge> edges = new ArrayList<>(dag.edges());
    onNodes.accept(nodes);
    onEdges.accept(edges);
    return new Dag(dag.seed(), nodes, edges, dag.failing(), dag.slow());
  }
}
