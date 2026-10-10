// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link ExecutionControlController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.UnitTestShouldIncludeAssert",
})
class ExecutionControlControllerTest {

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The client. */
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    final WorkflowProgress running =
        new WorkflowProgress(
            "e1", "s1", "wf", "RUNNING", List.of(), LocalDateTime.now(), null, null);
    when(controlBus.getCurrentProgress("e1")).thenReturn(running);
    when(controlBus.getProgress("s1", "e1")).thenReturn(running);
    client =
        WebTestClient.bindToController(
                new ExecutionControlController(controlBus, new ExecutionAccess(controlBus)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
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
}
