// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.TreeControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link ExecutionController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods"
})
@ExpectedErrorLog("ERROR com.infenia.yukta.exception.GlobalExceptionHandler - ")
class ExecutionControllerTest {

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The tracker, which knows the runs of a tree. */
  private TaskTrackerService tracker;

  /** The client. */
  private WebTestClient client;

  private static WorkflowProgress progress(final String session, final TaskStatus status) {
    return new WorkflowProgress("e1", session, "wf", status, List.of(), Instant.now(), null, null);
  }

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    tracker = mock(TaskTrackerService.class);
    client =
        WebTestClient.bindToController(
                new ExecutionController(
                    controlBus, new ExecutionAccess(controlBus), new TreeControl(tracker), tracker))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testStopWithTree_stopsTheRunsBelowToo_andTreeListsThem() {
    final List<WorkflowExecutionSummary> tree =
        List.of(
            new WorkflowExecutionSummary(
                "e1", "wf", TaskStatus.RUNNING, Instant.now(), null, null, "e1", null),
            new WorkflowExecutionSummary(
                "c1", "child", TaskStatus.RUNNING, Instant.now(), null, "e1", "e1", null));
    when(tracker.getTree("e1")).thenReturn(tree);
    when(controlBus.stopExecution(any(), any(), any()))
        .thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(controlBus.getCurrentProgress("e1")).thenReturn(progress("s1", TaskStatus.RUNNING));

    client.post().uri("/api/executions/e1/stop?tree=true").exchange().expectStatus().isOk();
    client
        .get()
        .uri("/api/executions/e1/tree")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[1].executionId")
        .isEqualTo("c1")
        .jsonPath("$.data[1].parentExecutionId")
        .isEqualTo("e1");

    verify(controlBus).stopExecution("e1", "Stopped via REST API", StopMode.DRAIN);
    verify(controlBus).stopExecution("c1", "Stopped via REST API", StopMode.DRAIN);
  }

  @Test
  void testStopAtNewAndLegacyPaths() {
    when(controlBus.stopExecution(any(), any(), any())).thenReturn(Mono.just("e1"));

    for (final String uri :
        List.of("/api/executions/e1/stop", "/api/workflow/executions/e1/stop")) {
      client
          .post()
          .uri(uri)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.executionId")
          .isEqualTo("e1");
    }
  }

  @Test
  void testStopPassesTheModeOn() {
    when(controlBus.stopExecution(any(), any(), any())).thenReturn(Mono.just("e1"));

    client.post().uri("/api/executions/e1/stop?mode=KILL").exchange().expectStatus().isOk();

    verify(controlBus).stopExecution("e1", "Stopped via REST API", StopMode.KILL);
  }

  @Test
  void testStopOfUnknownExecutionIs404NotMaskedErrors() {
    when(controlBus.stopExecution(any(), any(), any()))
        .thenReturn(Mono.error(new NotFoundException("Execution not found: e1")));

    client.post().uri("/api/executions/e1/stop").exchange().expectStatus().isNotFound();
  }

  @Test
  void testValidationErrorFromCoreIs400NotNotFound() {
    when(controlBus.restartWorkflow("e1"))
        .thenReturn(Mono.error(new IllegalArgumentException("cannot restart a running run")));

    client.post().uri("/api/executions/e1/restart").exchange().expectStatus().isBadRequest();
  }

  @Test
  void testNullMessageExceptionDoesNotBreakTheHandler() {
    when(controlBus.restartWorkflow("e1")).thenReturn(Mono.error(new IllegalArgumentException()));

    client.post().uri("/api/executions/e1/restart").exchange().expectStatus().isBadRequest();
  }

  @Test
  void testInternalFailureIs500WithoutLeakingMessage() {
    when(controlBus.restartFromNode("e1", "n1"))
        .thenReturn(Mono.error(new IllegalStateException("/var/lib/yukta/secret.json missing")));

    client
        .post()
        .uri("/api/executions/e1/restart/n1")
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody(String.class)
        .value(body -> assertThat(body).doesNotContain("secret.json").contains("Reference"));
  }

  @Test
  void testRestartLegacyPathWorks() {
    when(controlBus.restartWorkflow("e1")).thenReturn(Mono.just("e2"));

    client
        .post()
        .uri("/api/workflow/executions/e1/restart")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e2");
  }

  @Test
  void testStatusByExecutionOnly() {
    when(controlBus.getCurrentProgress("e1")).thenReturn(progress("s1", TaskStatus.RUNNING));

    client
        .get()
        .uri("/api/executions/e1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.status")
        .isEqualTo("RUNNING");
  }

  @Test
  void testLegacyStatusChecksSessionOwnership() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("s1", TaskStatus.RUNNING));

    client.get().uri("/api/workflow/s1/status/e1").exchange().expectStatus().isOk();
    client.get().uri("/api/workflow/other/status/e1").exchange().expectStatus().isNotFound();
  }

  @Test
  void statusWithSessionQueryChecksOwnership() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("s1", TaskStatus.SUCCESS));

    client.get().uri("/api/executions/e1?sessionId=s1").exchange().expectStatus().isOk();
    client.get().uri("/api/executions/e1?sessionId=other").exchange().expectStatus().isNotFound();
  }

  @Test
  void testStatusStreamCarriesEventIdsAndRejectsForeignSession() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("s1", TaskStatus.RUNNING));
    when(controlBus.watchExecution("e1", true))
        .thenReturn(
            Flux.just(progress("s1", TaskStatus.RUNNING), progress("s1", TaskStatus.SUCCESS)));

    final List<String> events =
        client
            .get()
            .uri("/api/workflow/s1/status/e1/stream")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .blockOptional()
            .orElseThrow();
    final String body = String.join("\n", events);
    assertThat(body).contains("RUNNING").contains("SUCCESS");

    client.get().uri("/api/workflow/other/status/e1/stream").exchange().expectStatus().isNotFound();
    verify(controlBus, never()).watchExecution("e1", false);
  }

  @Test
  void testStatusStreamIgnoresLastEventId() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("s1", TaskStatus.RUNNING));
    when(controlBus.watchExecution("e1", false))
        .thenReturn(
            Flux.just(progress("s1", TaskStatus.RUNNING), progress("s1", TaskStatus.SUCCESS)));

    final String body =
        String.join(
            "\n",
            client
                .get()
                .uri("/api/workflow/s1/status/e1/stream?includeHistory=false")
                .header("Last-Event-ID", "41")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .blockOptional()
                .orElseThrow());

    assertThat(body).contains("RUNNING").contains("SUCCESS");
  }
}
