// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.service.result.ExecutionResults;
import com.infenia.yukta.service.result.ResultEvent;
import com.infenia.yukta.testing.Fixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** {@link WorkflowTestRunner}: which nodes are swapped and how the report is built. */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.AvoidDuplicateLiterals"})
class WorkflowTestRunnerTest {

  private final PluginRegistry registry = mock(PluginRegistry.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final ExecutionResultService results = mock(ExecutionResultService.class);
  private final WorkflowTestRunner runner =
      new WorkflowTestRunner(registry, orchestrator, tracker, results);
  private final List<WorkflowDefinition> prepared = new ArrayList<>();

  private static Plugin plugin(final PluginCategory category) {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getCategory()).thenReturn(category);
    return plugin;
  }

  private WorkflowDefinition definition() {
    final Plugin trigger = plugin(PluginCategory.TRIGGER);
    final Plugin processor = plugin(PluginCategory.PROCESSOR);
    final Plugin terminal = plugin(PluginCategory.TERMINAL);
    when(registry.get("TRIG")).thenReturn(trigger);
    when(registry.get("PROC")).thenReturn(processor);
    when(registry.get("TERM")).thenReturn(terminal);
    return Fixtures.workflow(
        "wf",
        "d",
        List.of(
            Fixtures.node("t", "TRIG", Map.of()),
            Fixtures.node("p", "PROC", Map.of()),
            Fixtures.node("gone", "UNKNOWN", Map.of()),
            Fixtures.node("end", "TERM", Map.of())),
        List.of(new Edge("t", "p", null), new Edge("p", "end", null)));
  }

  private void engine(final TaskStatus status, final Mono<Void> execute) {
    when(orchestrator.prepareWorkflow(any()))
        .thenAnswer(call -> {
          prepared.add(call.getArgument(0));
          return Mono.just(mock(com.infenia.yukta.model.workflow.PreparedWorkflow.class));
        });
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(execute);
    final List<TaskProgress> tasks =
        List.of(
            new TaskProgress("t", "TRIG", TaskStatus.SUCCESS, Instant.now(), null, Map.of(), null),
            new TaskProgress("p", "PROC", TaskStatus.PENDING, null, null, Map.of(), null));
    when(tracker.getProgressByExecutionId(anyString()))
        .thenReturn(
            new WorkflowProgress(
                "e",
                "s",
                "wf",
                status,
                tasks,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null));
  }

  private static WorkflowTestSpec.Expect expect(
      final String status,
      final List<String> reached,
      final List<String> notReached,
      final Map<String, Map<String, Object>> nodes) {
    return new WorkflowTestSpec.Expect(status, reached, notReached, nodes);
  }

  @Test
  void swapsTriggerTerminalAndStubbedProcessor_andChecksEveryKindOfAssertion() {
    engine(TaskStatus.SUCCESS, Mono.empty());
    when(results.read(anyString(), anyLong()))
        .thenReturn(
            new ExecutionResults(
                "e",
                true,
                "SUCCESS",
                false,
                3,
                List.of(
                    new ResultEvent(
                        ResultEvent.Kind.RESULT, "end", 1, "text", null, false, Instant.now()),
                    new ResultEvent(
                        ResultEvent.Kind.NODE_END, "end", 2, null, "SUCCESS", false, Instant.now()),
                    new ResultEvent(
                        ResultEvent.Kind.RESULT,
                        "end",
                        3,
                        Map.of("a", Map.of("b", 1), "c", "x"),
                        null,
                        false,
                        Instant.now()))));
    final var spec =
        new WorkflowTestSpec(
            null,
            Map.of("p", List.of(new WorkflowTestSpec.Stub(Map.of("k", "v"), "success"))),
            expect(
                "FAILURE",
                List.of("t", "p"),
                List.of("p", "t", "nope"),
                Map.of("end", Map.of("a.b", 1, "payload.c", "x", "missing", "y"))),
            List.of("p"),
            null);

    final TestReport report = runner.run("s", definition(), spec).block();

    assertThat(report.status()).isEqualTo("SUCCESS");
    assertThat(report.passed()).isFalse();
    assertThat(report.assertions())
        .extracting(TestReport.Check::name, TestReport.Check::passed)
        .contains(
            org.assertj.core.api.Assertions.tuple("status is FAILURE", false),
            org.assertj.core.api.Assertions.tuple("reached t", true),
            org.assertj.core.api.Assertions.tuple("reached p", false),
            org.assertj.core.api.Assertions.tuple("not reached p", true),
            org.assertj.core.api.Assertions.tuple("not reached t", false),
            org.assertj.core.api.Assertions.tuple("not reached nope", true),
            org.assertj.core.api.Assertions.tuple("end a.b = 1", true),
            org.assertj.core.api.Assertions.tuple("end payload.c = x", true),
            org.assertj.core.api.Assertions.tuple("end missing = y", false));
    assertThat(prepared.getFirst().nodes())
        .extracting(WorkflowDefinition.Node::type)
        .containsExactly(
            StubPlugins.TRIGGER, StubPlugins.PROCESSOR, "UNKNOWN", StubPlugins.TERMINAL);
  }

  @Test
  void aTriggerWithoutStubEmitsTheInput_anAllowedProcessorRuns_andARunErrorIsReported() {
    engine(TaskStatus.FAILURE, Mono.error(new IllegalStateException("boom")));
    final var spec = new WorkflowTestSpec(Map.of("env", "prod"), Map.of(), null, List.of("p"), 5);

    final TestReport report = runner.run("s", definition(), spec).block();

    assertThat(report.status()).isEqualTo("FAILURE");
    assertThat(report.passed()).isTrue();
    assertThat(prepared.getFirst().nodes().get(0).config().toString()).contains("env=prod");
    assertThat(prepared.getFirst().nodes().get(1).type()).isEqualTo("PROC");
  }

  @Test
  void aProcessorWithoutStubAndNoAllowListRunsForReal() {
    engine(TaskStatus.SUCCESS, Mono.empty());
    final var spec = new WorkflowTestSpec(null, null, null, null, null);

    assertThat(runner.run("s", definition(), spec).block().passed()).isTrue();
    assertThat(prepared.getFirst().nodes().get(1).type()).isEqualTo("PROC");
  }

  @Test
  void aProcessorNeitherStubbedNorAllowedIsRefused() {
    final var spec = new WorkflowTestSpec(null, Map.of(), null, List.of("other"), null);

    assertThatThrownBy(() -> runner.run("s", definition(), spec).block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Node 'p' (PROC) has no stub");
    org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never()).prepareWorkflow(any());
  }
}
