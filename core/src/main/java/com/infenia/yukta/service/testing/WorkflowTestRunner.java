// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.service.result.ResultEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Runs a workflow with its nodes stubbed and checks what happened. Triggers emit the start payload
 * (or their stub), terminals do nothing, and a processor emits its stub when it has one; the rest
 * run for real, so stub every node with a side effect (or list the ones that may run in {@code
 * allow}). Only terminal nodes record the payloads they received, so payload assertions name a
 * terminal.
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
public class WorkflowTestRunner {

  /** Resolves the plugin of a node, to tell triggers from processors from terminals. */
  private final PluginRegistry registry;

  /** Runs the stubbed workflow. */
  private final WorkflowOrchestrator orchestrator;

  /** Reads which nodes ran. */
  private final TaskTrackerService tracker;

  /** Reads what the terminals received. */
  private final ExecutionResultService results;

  /**
   * Runs the test.
   *
   * @param sessionId the session the run belongs to
   * @param definition the workflow
   * @param spec the test
   * @return the report; an error if the workflow cannot be prepared or a processor may not run
   */
  public Mono<TestReport> run(
      final String sessionId, final WorkflowDefinition definition, final WorkflowTestSpec spec) {
    final Map<String, Object> input = spec.input() == null ? Map.of() : spec.input();
    final String executionId = "test-" + UUID.randomUUID();
    final long seconds = spec.timeoutSeconds() == null ? 60 : spec.timeoutSeconds();
    return Mono.fromCallable(() -> stubbed(definition, spec, input))
        .flatMap(orchestrator::prepareWorkflow)
        .flatMap(prepared ->
            orchestrator
                .execute(sessionId, definition.workflowId(), executionId, prepared, input)
                // a failing run is a result to report, not an error of the test
                .onErrorResume(_ -> Mono.empty())
                // the tracker applies the end of the run a moment after the stream completes
                .then(
                    Mono.fromSupplier(() -> tracker.getProgressByExecutionId(executionId))
                        .filter(p -> p.status().isTerminal())
                        .repeatWhenEmpty(again -> again.delayElements(Duration.ofMillis(25)))
                        .timeout(Duration.ofSeconds(seconds))))
        .then(Mono.fromSupplier(() -> report(executionId, spec)));
  }

  private WorkflowDefinition stubbed(
      final WorkflowDefinition def, final WorkflowTestSpec spec, final Map<String, Object> input) {
    final List<WorkflowDefinition.Node> nodes =
        def.nodes().stream().map(node -> swap(node, spec, input)).toList();
    return new WorkflowDefinition(
        def.workflowId(),
        def.description(),
        nodes,
        def.edges(),
        def.supervisors(),
        def.groupColors(),
        def.admission(),
        def.durable());
  }

  /** The node as the test runs it: a stub, or the node itself when it may run for real. */
  private WorkflowDefinition.Node swap(
      final WorkflowDefinition.Node node,
      final WorkflowTestSpec spec,
      final Map<String, Object> input) {
    final Plugin plugin = registry.get(node.type());
    final List<WorkflowTestSpec.Stub> stub =
        spec.stubs() == null ? null : spec.stubs().get(node.nodeId());
    final PluginCategory category = plugin == null ? null : plugin.getCategory();
    if (category == PluginCategory.TERMINAL) {
      return replace(node, StubPlugins.TERMINAL, List.of());
    }
    if (category == PluginCategory.TRIGGER) {
      return replace(
          node,
          StubPlugins.TRIGGER,
          stub != null ? stub : List.of(new WorkflowTestSpec.Stub(input, null)));
    }
    if (stub != null) {
      return replace(node, StubPlugins.PROCESSOR, stub);
    }
    return plugin == null ? node : unstubbed(node, spec.allow());
  }

  /** A node that runs for real: refused unless it is allowed, when there is an allow list. */
  private static WorkflowDefinition.Node unstubbed(
      final WorkflowDefinition.Node node, final List<String> allow) {
    if (allow != null && !allow.contains(node.nodeId())) {
      throw new IllegalArgumentException(
          "Node '"
              + node.nodeId()
              + "' ("
              + node.type()
              + ") has no stub and is not in allow; stub it or allow it to run");
    }
    return node;
  }

  private static WorkflowDefinition.Node replace(
      final WorkflowDefinition.Node node,
      final String type,
      final List<WorkflowTestSpec.Stub> emits) {
    final List<Map<String, Object>> emit =
        emits.stream()
            .map(stub ->
                stub.port() == null
                    ? Map.of("payload", payloadOf(stub))
                    : Map.of("payload", payloadOf(stub), "port", stub.port()))
            .toList();
    return new WorkflowDefinition.Node(
        node.nodeId(), type, Map.of("emit", emit), node.group(), node.color(), node.description());
  }

  private static Object payloadOf(final WorkflowTestSpec.Stub stub) {
    return stub.payload() == null ? Map.of() : stub.payload();
  }

  private TestReport report(final String executionId, final WorkflowTestSpec spec) {
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    final String status = progress.status().name();
    final List<TestReport.Check> checks = new ArrayList<>();
    final WorkflowTestSpec.Expect expect = spec.expect();
    if (expect != null) {
      if (expect.status() != null) {
        checks.add(check("status is " + expect.status(), expect.status().equals(status), status));
      }
      final Map<String, Boolean> started = new java.util.concurrent.ConcurrentHashMap<>();
      for (final TaskProgress task : progress.tasks()) {
        started.put(task.nodeId(), task.startTime() != null);
      }
      for (final String node : orEmpty(expect.reached())) {
        checks.add(check("reached " + node, started.getOrDefault(node, false), "did not start"));
      }
      for (final String node : orEmpty(expect.notReached())) {
        checks.add(check("not reached " + node, !started.getOrDefault(node, false), "started"));
      }
      if (expect.nodes() != null) {
        checkPayloads(executionId, expect.nodes(), checks);
      }
    }
    return new TestReport(checks.stream().allMatch(TestReport.Check::passed), status, checks);
  }

  private void checkPayloads(
      final String executionId,
      final Map<String, Map<String, Object>> expected,
      final List<TestReport.Check> checks) {
    final List<ResultEvent> events = results.read(executionId, 0).events();
    expected.forEach((nodeId, fields) ->
        fields.forEach((path, want) -> {
          final String name = nodeId + " " + path + " = " + want;
          final Object got =
              events.stream()
                  .filter(e -> e.kind() == ResultEvent.Kind.RESULT && nodeId.equals(e.nodeId()))
                  .map(e -> valueAt(e.payload(), path))
                  .filter(java.util.Objects::nonNull)
                  .findFirst()
                  .orElse(null);
          checks.add(check(name, want.equals(got), String.valueOf(got)));
        }));
  }

  /** A dotted path into nested maps; a leading {@code payload.} is the payload itself. */
  private static Object valueAt(final Object payload, final String path) {
    Object current = payload;
    final String trimmed = path.startsWith("payload.") ? path.substring("payload.".length()) : path;
    for (final String part : trimmed.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(part);
    }
    return current;
  }

  private static TestReport.Check check(
      final String name, final boolean passed, final String actual) {
    return new TestReport.Check(name, passed, passed ? null : "got " + actual);
  }

  private static <T> List<T> orEmpty(final List<T> list) {
    return list == null ? List.of() : list;
  }
}
