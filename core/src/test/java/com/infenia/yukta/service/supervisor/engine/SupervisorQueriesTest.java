// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.control.SchedulerLimits;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputRequestQueries;
import com.infenia.yukta.service.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An engine reads only what its supervisor owns. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class SupervisorQueriesTest {

  private final SessionService sessions = mock(SessionService.class);
  private final ControlBusGateway bus = mock(ControlBusGateway.class);
  private final InputRequestQueries inputs = mock(InputRequestQueries.class);
  private final SupervisorQueries queries = new SupervisorQueries(sessions, bus, inputs);

  private static WorkflowExecutionSummary run(
      final String id, final String workflow, final TaskStatus status, final long start) {
    final WorkflowExecutionSummary run = mock(WorkflowExecutionSummary.class);
    when(run.executionId()).thenReturn(id);
    when(run.workflowId()).thenReturn(workflow);
    when(run.status()).thenReturn(status);
    when(run.startTime()).thenReturn(Instant.ofEpochSecond(start));
    return run;
  }

  private void history() {
    when(sessions.getSessionIds(false)).thenReturn(Flux.just("s"));
    final List<WorkflowExecutionSummary> runs =
        List.of(
            run("old", "w", TaskStatus.RUNNING, 1),
            run("new", "w", TaskStatus.RUNNING, 2),
            run("done", "w", TaskStatus.SUCCESS, 3),
            run("other", "x", TaskStatus.RUNNING, 4));
    when(bus.getHistory("s")).thenReturn(Mono.just(runs));
  }

  @Test
  void executionsOfOwnedWorkflowsComeNewestFirstAndCanBeNarrowedByStatus() {
    history();
    final EngineReads reads = queries.readsFor(new SupervisorScope.Workflow("w", "s"));

    assertThat(reads.executions("RUNNING", 10).collectList().block())
        .extracting(EngineReads.RunView::executionId)
        .containsExactly("new", "old");
    assertThat(reads.executions(null, 10).collectList().block()).hasSize(3);
    assertThat(reads.executions(null, 1).collectList().block())
        .extracting(EngineReads.RunView::executionId)
        .containsExactly("done");
  }

  @Test
  void sectionSeesTheRunsOfItsWorkflowButAnotherWorkflowIsHidden() {
    assertThat(SupervisorQueries.sees(new SupervisorScope.Section("w", "s", Set.of("n")), "s", "w"))
        .isTrue();
    assertThat(
            SupervisorQueries.sees(new SupervisorScope.Section("w", null, Set.of("n")), "s", "w"))
        .isTrue();
    assertThat(SupervisorQueries.sees(new SupervisorScope.Section("w", "t", Set.of("n")), "s", "w"))
        .isFalse();
    assertThat(SupervisorQueries.sees(new SupervisorScope.Section("w", "s", Set.of("n")), "s", "x"))
        .isFalse();
  }

  @Test
  void openInputsAreLimitedToTheOwnedUnits() {
    final InputRequest mine = request("s", "w", "n1");
    final InputRequest theirs = request("s", "x", "n1");
    when(inputs.list(any(), any(), any(), any())).thenReturn(Flux.just(mine, theirs));

    assertThat(
            queries
                .readsFor(new SupervisorScope.Workflow("w", "s"))
                .openInputs(10)
                .collectList()
                .block())
        .containsExactly(mine);
  }

  @Test
  void globalScopeOwnsNothingSoItReadsNothing() {
    history();
    final InputRequest open = request("s", "w", "n1");
    when(inputs.list(any(), any(), any(), any())).thenReturn(Flux.just(open));
    final EngineReads reads = queries.readsFor(new SupervisorScope.Global());

    assertThat(reads.executions(null, 10).collectList().block()).isEmpty();
    assertThat(reads.openInputs(10).collectList().block()).isEmpty();
  }

  @Test
  void statusIsReadInAnyCaseAndAnUnknownOneNamesTheChoices() {
    history();
    final EngineReads reads = queries.readsFor(new SupervisorScope.Workflow("w", "s"));

    assertThat(reads.executions("running", 10).collectList().block()).hasSize(2);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> reads.executions("nope", 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUNNING");
  }

  @Test
  void nothingIsReadBeforeTheSupervisorIsAttached() {
    assertThat(EngineReads.NONE.executions(null, 5).collectList().block()).isEmpty();
    assertThat(EngineReads.NONE.openInputs(5).collectList().block()).isEmpty();
  }

  private static InputRequest request(
      final String session, final String workflow, final String node) {
    final InputRequest request = mock(InputRequest.class);
    when(request.sessionId()).thenReturn(session);
    when(request.workflowId()).thenReturn(workflow);
    when(request.nodeId()).thenReturn(node);
    return request;
  }

  @Test
  void statusOfReadsTheRunFromTheHistory() {
    history();

    assertThat(queries.statusOf("s", "done").block()).isEqualTo("SUCCESS");
    assertThat(queries.statusOf("s", "missing").blockOptional()).isEmpty();
  }

  private static TaskProgress task(final String node, final TaskStatus status) {
    final TaskProgress task = mock(TaskProgress.class);
    when(task.nodeId()).thenReturn(node);
    when(task.status()).thenReturn(status);
    return task;
  }

  private void progressOfNew() {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.sessionId()).thenReturn("s");
    when(progress.workflowId()).thenReturn("w");
    final TaskProgress compile = task("build.compile", TaskStatus.RUNNING);
    final TaskProgress deploy = task("deploy.prod", TaskStatus.PENDING);
    when(progress.tasks()).thenReturn(List.of(compile, deploy));
    when(bus.getProgress("s", "new")).thenReturn(progress);
  }

  @Test
  void anExecutionShowsTheStatusOfTheOwnedNodesOnly() {
    history();
    progressOfNew();

    final var whole = queries.readsFor(new SupervisorScope.Workflow("w", "s"));
    assertThat(whole.execution("new").block().nodes())
        .containsOnlyKeys("build.compile", "deploy.prod");

    final var section = queries.readsFor(new SupervisorScope.Section("w", "s", Set.of("build.*")));
    final var detail = section.execution("new").block();
    assertThat(detail.run().executionId()).isEqualTo("new");
    assertThat(detail.nodes()).containsOnlyKeys("build.compile");

    final var runLevel = queries.readsFor(new SupervisorScope.Run("w", "s"));
    assertThat(runLevel.execution("new").block().nodes()).isEmpty();
  }

  @Test
  void anExecutionThatIsNotOwnedOrUnknownIsNotReadable() {
    history();
    progressOfNew();

    assertThat(
            queries
                .readsFor(new SupervisorScope.Workflow("x", "s"))
                .execution("new")
                .blockOptional())
        .isEmpty();
    assertThat(
            queries
                .readsFor(new SupervisorScope.Workflow("w", "s"))
                .execution("zzz")
                .blockOptional())
        .isEmpty();
    assertThat(queries.readsFor(new SupervisorScope.Global()).execution("new").blockOptional())
        .isEmpty();
    assertThat(EngineReads.NONE.execution("new").blockOptional()).isEmpty();
  }

  private static PluginLogEntry line(final String node, final String text, final LogLevel level) {
    final PluginLogEntry entry = mock(PluginLogEntry.class);
    when(entry.pluginId()).thenReturn(node);
    when(entry.message()).thenReturn(text);
    when(entry.logLevel()).thenReturn(level);
    return entry;
  }

  @Test
  void logsAreTheNewestLinesOfOwnedNodesWithoutSecretsAndCutShort() {
    history();
    final PluginLogStore store = mock(PluginLogStore.class);
    queries.setLogStore(store);
    final PluginLogEntry first = line("build.compile", "first", LogLevel.INFO);
    final PluginLogEntry leaked = line("deploy.prod", "token=abc123secretvalue", LogLevel.INFO);
    final PluginLogEntry password =
        line("build.compile", "password=hunter2 and more", LogLevel.WARN);
    final PluginLogEntry long5000 = line("build.compile", "x".repeat(5000), LogLevel.INFO);
    when(store.readExecution("new")).thenReturn(Flux.just(first, leaked, password, long5000));

    final var all = queries.readsFor(new SupervisorScope.Workflow("w", "s"));
    final List<String> lines = all.logs("new", 3).collectList().block();
    assertThat(lines).hasSize(3);
    assertThat(String.join("\n", lines))
        .doesNotContain("abc123secretvalue")
        .doesNotContain("hunter2");
    assertThat(lines.get(2)).endsWith("...").hasSizeLessThan(2100);

    final var section = queries.readsFor(new SupervisorScope.Section("w", "s", Set.of("build.*")));
    assertThat(section.logs("new", 50).collectList().block())
        .noneMatch(l -> l.contains("deploy.prod"))
        .hasSize(3);
    assertThat(all.logs("zzz", 5).collectList().block()).isEmpty();
  }

  @Test
  void withoutLogStoreThereAreNoLogs() {
    history();
    assertThat(
            queries
                .readsFor(new SupervisorScope.Workflow("w", "s"))
                .logs("new", 5)
                .collectList()
                .block())
        .isEmpty();
    assertThat(EngineReads.NONE.logs("new", 5).collectList().block()).isEmpty();
  }

  @Test
  void workflowShowsOnlyOwnedNodesAndTheEdgesBetweenThem() {
    final WorkflowDefinition definition = mock(WorkflowDefinition.class);
    final WorkflowDefinition.Node compile = mock(WorkflowDefinition.Node.class);
    when(compile.nodeId()).thenReturn("build.compile");
    when(compile.type()).thenReturn("PROCESS_EXECUTOR");
    when(compile.group()).thenReturn("build");
    final WorkflowDefinition.Node test = mock(WorkflowDefinition.Node.class);
    when(test.nodeId()).thenReturn("build.test");
    when(test.type()).thenReturn("PROCESS_EXECUTOR");
    final WorkflowDefinition.Node deploy = mock(WorkflowDefinition.Node.class);
    when(deploy.nodeId()).thenReturn("deploy.prod");
    when(deploy.type()).thenReturn("HTTP_REQUEST");
    final WorkflowDefinition.Edge inside = mock(WorkflowDefinition.Edge.class);
    when(inside.source()).thenReturn("build.compile");
    when(inside.target()).thenReturn("build.test");
    final WorkflowDefinition.Edge across = mock(WorkflowDefinition.Edge.class);
    when(across.source()).thenReturn("build.test");
    when(across.target()).thenReturn("deploy.prod");
    when(definition.nodes()).thenReturn(List.of(compile, test, deploy));
    when(definition.edges()).thenReturn(List.of(inside, across));
    when(sessions.getSessionWorkflow("s", "w")).thenReturn(Mono.just(definition));

    final var section = queries.readsFor(new SupervisorScope.Section("w", "s", Set.of("build.*")));
    final var shape = section.workflow("s", "w").block();
    assertThat(shape.nodes())
        .extracting(EngineReads.NodeView::nodeId)
        .containsExactly("build.compile", "build.test");
    assertThat(shape.nodes().get(0).group()).isEqualTo("build");
    assertThat(shape.edges())
        .containsExactly(new EngineReads.EdgeView("build.compile", "build.test"));

    assertThat(section.workflow("s", "other").blockOptional()).isEmpty();
    assertThat(
            queries
                .readsFor(new SupervisorScope.Workflow("x", "s"))
                .workflow("s", "w")
                .blockOptional())
        .isEmpty();
    when(sessions.getSessionWorkflow("s", "gone"))
        .thenReturn(Mono.error(new IllegalStateException("x")));
    assertThat(
            queries
                .readsFor(new SupervisorScope.Workflow("gone", "s"))
                .workflow("s", "gone")
                .blockOptional())
        .isEmpty();
    assertThat(EngineReads.NONE.workflow("s", "w").blockOptional()).isEmpty();
  }

  @Test
  void onlyTheOwnerOfTheServerReadsTheScheduler() {
    when(bus.limits()).thenReturn(Mono.just(new SchedulerLimits(4, 2, 1, 3, 100)));

    assertThat(queries.readsFor(new SupervisorScope.Server()).scheduler().block())
        .isEqualTo(new EngineReads.SchedulerView(4, 2, 1, 3));
    assertThat(queries.readsFor(new SupervisorScope.Workflow("w", "s")).scheduler().blockOptional())
        .isEmpty();
    assertThat(EngineReads.NONE.scheduler().blockOptional()).isEmpty();
  }
}
