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
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.time.LocalDateTime;
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
  "PMD.LawOfDemeter",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
})
class ExecutionControllerTest {

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The client. */
  private WebTestClient client;

  private static WorkflowProgress progress(final String session, final String status) {
    return new WorkflowProgress(
        "e1", session, "wf", status, List.of(), LocalDateTime.now(), null, null);
  }

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    client =
        WebTestClient.bindToController(
                new ExecutionController(controlBus, new ExecutionAccess(controlBus)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testStopAtNewAndLegacyPaths() {
    when(controlBus.stopExecution(any(), any())).thenReturn(Mono.just("e1"));

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
  void testStopOfUnknownExecutionIs404NotMaskedErrors() {
    when(controlBus.stopExecution(any(), any()))
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
    when(controlBus.getCurrentProgress("e1")).thenReturn(progress("s1", "RUNNING"));

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
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("s1", "RUNNING"));

    client.get().uri("/api/workflow/s1/status/e1").exchange().expectStatus().isOk();
    client.get().uri("/api/workflow/other/status/e1").exchange().expectStatus().isNotFound();
  }

  @Test
  void testStatusStreamCarriesEventIdsAndRejectsForeignSession() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("s1", "RUNNING"));
    when(controlBus.watchExecution("e1", true))
        .thenReturn(Flux.just(progress("s1", "RUNNING"), progress("s1", "SUCCESS")));

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
}
