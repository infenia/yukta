// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionRejectedException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link WorkflowController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.ShortVariable"
})
class WorkflowControllerTest {

  /** The request body that starts workflow wf of session s1. */
  private static final String START_BODY = "{\"sessionId\":\"s1\",\"workflowId\":\"wf\"}";

  /** The workflow service. */
  private WorkflowService workflowService;

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The session service. */
  private SessionService sessionService;

  /** The client. */
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    controlBus = mock(ControlBusGateway.class);
    sessionService = mock(SessionService.class);
    client =
        WebTestClient.bindToController(
                new WorkflowController(workflowService, controlBus, sessionService))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testStartReturns202WithExecutionId() {
    when(workflowService.validateAndStartWorkflow(eq("s1"), eq("wf"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("e1", Mono.empty())));

    client
        .post()
        .uri("/api/workflow/start")
        .bodyValue(START_BODY)
        .header("Content-Type", "application/json")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e1");
  }

  @Test
  void testStartWithIdempotencyKeyUsesKeyedOverload() {
    when(workflowService.validateAndStartWorkflow(eq("s1"), eq("wf"), anyMap(), eq("k1")))
        .thenReturn(Mono.just(new WorkflowExecution("e9", Mono.empty())));

    client
        .post()
        .uri("/api/workflow/start")
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", "k1")
        .bodyValue(START_BODY)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e9");
  }

  @Test
  void testStartOfUnknownWorkflowIs404() {
    when(workflowService.validateAndStartWorkflow(any(), any(), anyMap()))
        .thenReturn(Mono.error(new NotFoundException("Workflow not found for session: s1")));

    client
        .post()
        .uri("/api/workflow/start")
        .header("Content-Type", "application/json")
        .bodyValue(START_BODY)
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void testStartRejectedByLimitIs429() {
    when(workflowService.validateAndStartWorkflow(any(), any(), anyMap()))
        .thenReturn(Mono.error(new ExecutionRejectedException("limit reached")));

    client
        .post()
        .uri("/api/workflow/start")
        .header("Content-Type", "application/json")
        .bodyValue(START_BODY)
        .exchange()
        .expectStatus()
        .isEqualTo(429);
  }

  @Test
  void testStartFailureIsNot404AndDoesNotLeakMessage() {
    when(workflowService.validateAndStartWorkflow(any(), any(), anyMap()))
        .thenReturn(Mono.error(new RuntimeException("jdbc:secret@10.0.0.5")));

    client
        .post()
        .uri("/api/workflow/start")
        .header("Content-Type", "application/json")
        .bodyValue(START_BODY)
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody(String.class)
        .value(body -> assertThat(body).doesNotContain("secret"));
  }

  @Test
  void testStopWorkflowReturnsStoppedIds() {
    when(controlBus.stopWorkflow(eq("s1"), eq("wf"), any(), eq(StopMode.DRAIN)))
        .thenReturn(Mono.just(List.of("e1", "e2")));

    client
        .post()
        .uri("/api/workflow/s1/wf/stop")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executionIds[1]")
        .isEqualTo("e2");
  }

  @Test
  void testStopWorkflowPassesTheRequestedMode() {
    when(controlBus.stopWorkflow(eq("s1"), eq("wf"), any(), eq(StopMode.KILL)))
        .thenReturn(Mono.just(List.of("e1")));

    client
        .post()
        .uri("/api/workflow/s1/wf/stop?mode=KILL")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executionIds[0]")
        .isEqualTo("e1");
  }

  @Test
  void testStopWorkflowRejectsAnUnknownMode() {
    client.post().uri("/api/workflow/s1/wf/stop?mode=NOW").exchange().expectStatus().isBadRequest();
  }

  private static SessionConfigResponse config() {
    return new SessionConfigResponse("s1", "n", "d", "i", Map.of(), "/p", Map.of());
  }

  private static WorkflowExecutionSummary run(final String id) {
    return new WorkflowExecutionSummary(id, "wf", "SUCCESS", LocalDateTime.now(), null);
  }

  @Test
  void testHistoryIsPagedWithNextCursor() {
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config()));
    when(controlBus.getHistory("s1"))
        .thenReturn(Mono.just(List.of(run("e1"), run("e2"), run("e3"))));

    client
        .get()
        .uri("/api/workflow/s1/history?limit=2")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Next-Cursor", "e2")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2);

    client
        .get()
        .uri("/api/workflow/s1/history?limit=2&cursor=e2")
        .exchange()
        .expectHeader()
        .doesNotExist("X-Next-Cursor")
        .expectBody()
        .jsonPath("$.data[0].executionId")
        .isEqualTo("e3");
  }

  @Test
  void testHistoryOfUnknownSessionIs404() {
    when(sessionService.getSessionConfig("nope")).thenReturn(Mono.empty());

    client.get().uri("/api/workflow/nope/history").exchange().expectStatus().isNotFound();
  }
}
