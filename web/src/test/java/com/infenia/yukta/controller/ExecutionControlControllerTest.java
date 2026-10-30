// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.model.control.SchedulerLimits;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.TreeControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link ExecutionControlController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals"
})
class ExecutionControlControllerTest {

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The resumer. */
  private ExecutionResumer resumer;

  /** The tracker, which knows the runs of a tree. */
  private TaskTrackerService tracker;

  /** The scheduler behind the drain switch. */
  private final ExecutionScheduler scheduler =
      new ExecutionScheduler(
          0, 0, 10, ExecutionScheduler.OnLimit.QUEUE, java.time.Duration.ofSeconds(1));

  /** The client. */
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    resumer = mock(ExecutionResumer.class);
    tracker = mock(TaskTrackerService.class);
    final WorkflowProgress running =
        new WorkflowProgress(
            "e1", "s1", "wf", "RUNNING", List.of(), LocalDateTime.now(), null, null);
    when(controlBus.getCurrentProgress("e1")).thenReturn(running);
    when(controlBus.getProgress("s1", "e1")).thenReturn(running);
    client =
        WebTestClient.bindToController(
                new ExecutionControlController(
                    controlBus,
                    new ExecutionAccess(controlBus),
                    resumer,
                    new TreeControl(tracker),
                    scheduler))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testPauseAndResumeWithTree_reachTheUnfinishedRunsBelow() {
    when(tracker.getTree("e1"))
        .thenReturn(
            List.of(
                new WorkflowExecutionSummary(
                    "e1", "wf", "RUNNING", LocalDateTime.now(), null, null, "e1", null),
                new WorkflowExecutionSummary(
                    "c1", "child", "RUNNING", LocalDateTime.now(), null, "e1", "e1", null),
                new WorkflowExecutionSummary(
                    "c2", "child", "SUCCESS", LocalDateTime.now(), null, "e1", "e1", null)));
    when(controlBus.pauseWorkflow(any())).thenReturn(Mono.empty());
    when(controlBus.resumeWorkflow("e1")).thenReturn(Mono.empty());
    when(controlBus.resumeWorkflow("c1")).thenReturn(Mono.error(new IllegalStateException("gone")));

    client.post().uri("/api/executions/e1/pause?tree=true").exchange().expectStatus().isOk();
    client.post().uri("/api/executions/e1/resume?tree=true").exchange().expectStatus().isOk();

    verify(controlBus).pauseWorkflow("e1");
    verify(controlBus).pauseWorkflow("c1");
    verify(controlBus, org.mockito.Mockito.never()).pauseWorkflow("c2");
    verify(controlBus).resumeWorkflow("c1");
  }

  @Test
  void testSignalNodeAnswersOk_andConflictWhenNobodyWaits() {
    when(controlBus.signalNode("e1", "n1", "approval", "approve", "fine")).thenReturn(Mono.empty());
    when(controlBus.signalNode("e1", "n2", "approval", "approve", null))
        .thenReturn(Mono.error(new com.infenia.yukta.service.ConflictException("not waiting")));

    client
        .post()
        .uri("/api/executions/e1/nodes/n1/signal?name=approval&decision=approve&data=fine")
        .exchange()
        .expectStatus()
        .isOk();
    client
        .post()
        .uri("/api/executions/e1/nodes/n2/signal?name=approval&decision=approve")
        .exchange()
        .expectStatus()
        .isEqualTo(409);
  }

  @Test
  void testPendingApprovalsAreListedForTheSession() {
    when(controlBus.pendingApprovals("s1"))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new com.infenia.yukta.model.control.PendingApproval(
                    "e1", "wf", "gate", "approve", java.time.Instant.EPOCH)));

    client
        .get()
        .uri("/api/sessions/s1/approvals")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].executionId")
        .isEqualTo("e1")
        .jsonPath("$.data[0].name")
        .isEqualTo("approve");
  }

  @Test
  void testRecoverAtNewAndLegacyPaths() {
    when(resumer.resume("e1")).thenReturn(Mono.just("e2"));

    client
        .post()
        .uri("/api/executions/e1/recover")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e2");
    client.post().uri("/api/workflow/s1/e1/recover").exchange().expectStatus().isOk();
  }

  @Test
  void testRecoverRefusedRunAnswersConflict_andUnknownRunNotFound() {
    when(resumer.resume("e1"))
        .thenReturn(Mono.error(new com.infenia.yukta.service.ConflictException("already resumed")));

    client.post().uri("/api/executions/e1/recover").exchange().expectStatus().isEqualTo(409);
    client.post().uri("/api/executions/nope/recover").exchange().expectStatus().isNotFound();
  }

  @Test
  void testPauseAndResumeAtNewAndLegacyPaths() {
    when(controlBus.pauseWorkflow("e1")).thenReturn(Mono.empty());
    when(controlBus.resumeWorkflow("e1")).thenReturn(Mono.empty());

    client.post().uri("/api/executions/e1/pause").exchange().expectStatus().isOk();
    client.post().uri("/api/workflow/s1/e1/pause").exchange().expectStatus().isOk();
    client.post().uri("/api/executions/e1/resume").exchange().expectStatus().isOk();
    client.post().uri("/api/workflow/s1/e1/resume").exchange().expectStatus().isOk();
    verify(controlBus, org.mockito.Mockito.times(2)).pauseWorkflow("e1");
  }

  @Test
  void testForeignSessionCannotControlExecution() {
    client.post().uri("/api/workflow/other/e1/pause").exchange().expectStatus().isNotFound();
  }

  @Test
  void testUnknownExecutionIs404() {
    client.post().uri("/api/executions/ghost/pause").exchange().expectStatus().isNotFound();
  }

  @Test
  void testNodeOperationsAtNewAndLegacyPaths() {
    when(controlBus.pauseNode("e1", "n1")).thenReturn(Mono.empty());
    when(controlBus.resumeNode("e1", "n1")).thenReturn(Mono.empty());
    when(controlBus.stopNode("e1", "n1", true, "why")).thenReturn(Mono.empty());
    when(controlBus.skipNode("e1", "n1", false)).thenReturn(Mono.empty());
    when(controlBus.enableStepMode("e1", "n1")).thenReturn(Mono.empty());
    when(controlBus.disableStepMode("e1", "n1")).thenReturn(Mono.empty());
    when(controlBus.stepNode("e1", "n1")).thenReturn(Mono.empty());

    client.post().uri("/api/executions/e1/nodes/n1/pause").exchange().expectStatus().isOk();
    client.post().uri("/api/workflow/s1/e1/node/n1/resume").exchange().expectStatus().isOk();
    client
        .post()
        .uri("/api/executions/e1/nodes/n1/stop?immediate=true&reason=why")
        .exchange()
        .expectStatus()
        .isOk();
    client
        .post()
        .uri("/api/executions/e1/nodes/n1/skip?skip=false")
        .exchange()
        .expectStatus()
        .isOk();
    client.post().uri("/api/executions/e1/nodes/n1/step/enable").exchange().expectStatus().isOk();
    client.post().uri("/api/workflow/s1/e1/node/n1/step/disable").exchange().expectStatus().isOk();
    client.post().uri("/api/executions/e1/nodes/n1/step").exchange().expectStatus().isOk();
    verify(controlBus).stepNode("e1", "n1");
  }

  @Test
  void testMissingNodeIs404ByTypeNotByMessageText() {
    when(controlBus.pauseNode(any(), any()))
        .thenReturn(Mono.error(new NotFoundException("renamed message: n9")));

    client.post().uri("/api/executions/e1/nodes/n9/pause").exchange().expectStatus().isNotFound();
  }

  @Test
  void testRejectedActionFromCoreIs400() {
    when(controlBus.skipNode("e1", "n1", true))
        .thenReturn(Mono.error(new IllegalArgumentException("Skipping is not supported")));

    client.post().uri("/api/executions/e1/nodes/n1/skip").exchange().expectStatus().isBadRequest();
  }

  @Test
  void testRetryReturnsNewExecution() {
    when(controlBus.retryNode("e1", "n1")).thenReturn(Mono.just("e2"));

    client
        .post()
        .uri("/api/executions/e1/nodes/n1/retry")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e2");
  }

  @Test
  void testBreakpointSetAndClear() {
    when(controlBus.addBreakpoint("e1", "n1", "payload.status", "error")).thenReturn(Mono.empty());
    when(controlBus.clearBreakpoint("e1", "n1")).thenReturn(Mono.empty());

    client
        .post()
        .uri("/api/executions/e1/nodes/n1/breakpoint?field=payload.status&equals=error")
        .exchange()
        .expectStatus()
        .isOk();
    client.delete().uri("/api/executions/e1/nodes/n1/breakpoint").exchange().expectStatus().isOk();
    verify(controlBus).addBreakpoint("e1", "n1", "payload.status", "error");
    verify(controlBus).clearBreakpoint("e1", "n1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"maxConcurrent=-1&maxPerWorkflow=2", "maxConcurrent=2&maxPerWorkflow=-1"})
  void changeLimitsRejectsNegativeValues(final String query) {
    client.post().uri("/api/scheduler/limits?" + query).exchange().expectStatus().isBadRequest();
    org.mockito.Mockito.verifyNoInteractions(controlBus);
  }

  @Test
  void testChangePriorityAndLimits() {
    when(controlBus.changePriority("e1", 7)).thenReturn(Mono.empty());
    when(controlBus.changeLimits(4, 2)).thenReturn(Mono.empty());

    client.post().uri("/api/executions/e1/priority?priority=7").exchange().expectStatus().isOk();
    client
        .post()
        .uri("/api/scheduler/limits?maxConcurrent=4&maxPerWorkflow=2")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.maxConcurrent")
        .isEqualTo(4);
    verify(controlBus).changePriority("e1", 7);
  }

  @Test
  void testGetLimitsReturnsTheSnapshot() {
    when(controlBus.limits()).thenReturn(Mono.just(new SchedulerLimits(4, 2, 3, 1, 100)));

    client
        .get()
        .uri("/api/scheduler/limits")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.maxConcurrent")
        .isEqualTo(4)
        .jsonPath("$.data.maxPerWorkflow")
        .isEqualTo(2)
        .jsonPath("$.data.running")
        .isEqualTo(3)
        .jsonPath("$.data.queued")
        .isEqualTo(1)
        .jsonPath("$.data.queueCapacity")
        .isEqualTo(100);
  }

  @Test
  void testCommandRefusedBySupervisorIs403() {
    when(controlBus.pauseWorkflow("e1"))
        .thenReturn(Mono.error(new ControlRejectedException("rules: maintenance window")));

    client
        .post()
        .uri("/api/executions/e1/pause")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.message")
        .value(org.hamcrest.Matchers.containsString("maintenance window"));
  }

  @Test
  void drain_refusesStartsAndReportsWhenIdle_untilUndone() {
    final ExecutionScheduler.Permit running = scheduler.acquire("s1", "wf").permit().block();

    client
        .post()
        .uri("/api/admin/drain")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.draining")
        .isEqualTo(true)
        .jsonPath("$.data.running")
        .isEqualTo(1)
        .jsonPath("$.data.idle")
        .isEqualTo(false);
    org.assertj.core.api.Assertions.assertThat(scheduler.acquire("s1", "wf").refused()).isTrue();

    running.release();
    client
        .get()
        .uri("/api/admin/drain")
        .exchange()
        .expectBody()
        .jsonPath("$.data.idle")
        .isEqualTo(true);
    client
        .delete()
        .uri("/api/admin/drain")
        .exchange()
        .expectBody()
        .jsonPath("$.data.draining")
        .isEqualTo(false);
    org.assertj.core.api.Assertions.assertThat(scheduler.acquire("s1", "wf").refused()).isFalse();
  }

  @Test
  void drainStatus_isNotIdleWhileStartWaits() {
    final ExecutionScheduler limited =
        new ExecutionScheduler(
            1, 0, 10, ExecutionScheduler.OnLimit.QUEUE, java.time.Duration.ofSeconds(1));
    final ExecutionScheduler.Permit running =
        java.util.Objects.requireNonNull(limited.acquire("s1", "wf").permit().block());
    limited.acquire("s1", "wf");
    running.release();
    limited.acquire("s1", "other");
    final WebTestClient waiting =
        WebTestClient.bindToController(
                new ExecutionControlController(
                    controlBus,
                    new ExecutionAccess(controlBus),
                    resumer,
                    new TreeControl(tracker),
                    limited))
            .build();

    waiting
        .get()
        .uri("/api/admin/drain")
        .exchange()
        .expectBody()
        .jsonPath("$.data.running")
        .isEqualTo(1)
        .jsonPath("$.data.queued")
        .isEqualTo(1)
        .jsonPath("$.data.idle")
        .isEqualTo(false);
  }
}
