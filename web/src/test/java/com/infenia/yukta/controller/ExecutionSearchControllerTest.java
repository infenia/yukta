// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.AvoidDuplicateLiterals"})
class ExecutionSearchControllerTest {

  private static final Instant NOON = LocalDateTime.of(2026, 1, 2, 12, 0).toInstant(ZoneOffset.UTC);

  private final SessionService sessions = mock(SessionService.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final WebTestClient client =
      WebTestClient.bindToController(new ExecutionSearchController(sessions, controlBus)).build();

  private static WorkflowExecutionSummary run(
      final String runId, final String workflow, final TaskStatus status, final Instant start) {
    return new WorkflowExecutionSummary(runId, workflow, status, start, null);
  }

  private void history() {
    when(sessions.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("a1", "wf", TaskStatus.SUCCESS, NOON),
                    run("a2", "wf", TaskStatus.FAILURE, NOON.minus(Duration.ofDays(1))))));
    when(controlBus.getHistory("s2"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("b1", "other", TaskStatus.SUCCESS, NOON.plus(Duration.ofHours(1))),
                    run("b2", "wf", TaskStatus.QUEUED, null))));
  }

  @Test
  void runsOfAllSessions_areMergedNewestFirst_withUnstartedLast() {
    history();

    client
        .get()
        .uri("/api/executions")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(4)
        .jsonPath("$.data[0].executionId")
        .isEqualTo("b1")
        .jsonPath("$.data[0].sessionId")
        .isEqualTo("s2")
        .jsonPath("$.data[3].executionId")
        .isEqualTo("b2");
  }

  @Test
  void sessionFiltersAndLimit_narrowTheRuns() {
    history();

    client
        .get()
        .uri("/api/executions?sessionId=s1&status=failure")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1)
        .jsonPath("$.data[0].executionId")
        .isEqualTo("a2");
    client
        .get()
        .uri("/api/executions?limit=2")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2);
    client
        .get()
        .uri("/api/executions?limit=0")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
  }

  @Test
  void workflowFilter_isAskedOfTheTrackerPerSession() {
    when(sessions.getSessionIds()).thenReturn(Flux.just("s1"));
    when(controlBus.getHistory("s1", "wf"))
        .thenReturn(Mono.just(List.of(run("a1", "wf", TaskStatus.SUCCESS, NOON))));

    client
        .get()
        .uri("/api/executions?workflowId=wf")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1)
        .jsonPath("$.data[0].executionId")
        .isEqualTo("a1");
  }

  @Test
  void hitsCarryWhoStartedTheRun() {
    when(sessions.getSessionIds()).thenReturn(Flux.just("s1"));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    new WorkflowExecutionSummary(
                        "a1",
                        "wf",
                        TaskStatus.SUCCESS,
                        NOON,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "yk check"),
                    new WorkflowExecutionSummary(
                        "a2",
                        "wf",
                        TaskStatus.SUCCESS,
                        NOON.minus(Duration.ofMinutes(1)),
                        null,
                        null,
                        null,
                        null,
                        null))));

    client
        .get()
        .uri("/api/executions")
        .exchange()
        .expectBody()
        .jsonPath("$.data[0].initiator")
        .isEqualTo("yk check")
        .jsonPath("$.data[1].initiator")
        .isEmpty();
  }

  @Test
  void hitsCarryNodeProgress() {
    when(sessions.getSessionIds()).thenReturn(Flux.just("s1"));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    new WorkflowExecutionSummary(
                        "a1",
                        "wf",
                        TaskStatus.FAILURE,
                        NOON,
                        null,
                        null,
                        null,
                        null,
                        new WorkflowExecutionSummary.Nodes(11, 100, "spotbugs")))));

    client
        .get()
        .uri("/api/executions")
        .exchange()
        .expectBody()
        .jsonPath("$.data[0].nodes.done")
        .isEqualTo(11)
        .jsonPath("$.data[0].nodes.total")
        .isEqualTo(100)
        .jsonPath("$.data[0].nodes.failed")
        .isEqualTo("spotbugs");
  }

  @Test
  void countsRunningAndQueuedNow_andFailuresInTheWindow() {
    final Instant now = Instant.now();
    when(sessions.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("r1", "wf", TaskStatus.RUNNING, now),
                    run("f1", "wf", TaskStatus.FAILURE, now.minus(Duration.ofHours(1))),
                    run("f2", "wf", TaskStatus.FAILURE, now.minus(Duration.ofHours(30))),
                    run("f3", "wf", TaskStatus.FAILURE, null))));
    when(controlBus.getHistory("s2"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("q1", "wf", TaskStatus.QUEUED, null),
                    run("e1", "wf", TaskStatus.ERROR, now))));

    client
        .get()
        .uri("/api/executions/counts")
        .exchange()
        .expectBody()
        .jsonPath("$.data.running")
        .isEqualTo(1)
        .jsonPath("$.data.queued")
        .isEqualTo(1)
        .jsonPath("$.data.failed")
        .isEqualTo(1);
    client
        .get()
        .uri("/api/executions/counts?hours=48")
        .exchange()
        .expectBody()
        .jsonPath("$.data.failed")
        .isEqualTo(2);
  }
}
