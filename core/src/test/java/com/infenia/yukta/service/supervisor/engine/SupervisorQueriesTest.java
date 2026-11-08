// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.EngineReads;
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
  void aSectionSeesTheRunsOfItsWorkflowButAnotherWorkflowIsHidden() {
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
}
