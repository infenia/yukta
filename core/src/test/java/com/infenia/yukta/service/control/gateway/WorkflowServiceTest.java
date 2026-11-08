// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.control.SupervisedControl;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for {@link WorkflowService}. */
@ExtendWith(MockitoExtension.class)
@ExpectedErrorLog("ERROR com.infenia.yukta.service.control.gateway.WorkflowService - ")
class WorkflowServiceTest {

  /** Mock control bus gateway. */
  @Mock private ControlBusGateway controlBus;

  /** Service under test. */
  private WorkflowService workflowService;

  /** Constructor. */
  /* default */ WorkflowServiceTest() {
    // Required by PMD
  }

  @BeforeEach
  void setUp() {
    workflowService = new WorkflowService(new SupervisedControl(controlBus, null, null, null));
  }

  @Test
  void testStartSuccess() {
    final String sessionId = "sess-123";
    final String workflowId = "wf-456";
    final String executionId = "exec-789";

    when(controlBus.startWorkflow(sessionId, workflowId, Map.of(), null))
        .thenReturn(Mono.just(executionId));

    StepVerifier.create(workflowService.start(sessionId, workflowId, Map.of(), null))
        .expectNext(executionId)
        .verifyComplete();
  }

  @Test
  void testStartNotFound() {
    final String sessionId = "sess-missing";
    final String workflowId = "wf-missing";

    when(controlBus.startWorkflow(sessionId, workflowId, Map.of(), null))
        .thenReturn(
            Mono.error(
                new IllegalArgumentException(
                    "Workflow not found for session: " + sessionId + ", workflow: " + workflowId)));

    StepVerifier.create(workflowService.start(sessionId, workflowId, Map.of(), null))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Workflow not found"))
        .verify();
  }

  @Test
  void testStartReturnsExecutionWithId() {
    final String sessionId = "sess-exec";
    final String workflowId = "wf-exec";
    final String executionId = "exec-001";

    when(controlBus.startWorkflow(sessionId, workflowId, Map.of(), null))
        .thenReturn(Mono.just(executionId));

    assertThat(workflowService.start(sessionId, workflowId, Map.of(), null).block())
        .isEqualTo(executionId);
  }

  @Test
  void testStartForwardsPayload() {
    final Map<String, Object> payload = Map.of("message", "hi");
    when(controlBus.startWorkflow("sess-p", "wf-p", payload, null)).thenReturn(Mono.just("exec-p"));

    StepVerifier.create(workflowService.start("sess-p", "wf-p", payload, null))
        .expectNext("exec-p")
        .verifyComplete();
  }

  @Test
  void testStartWithIdempotencyKeyForwardsTheKey() {
    when(controlBus.startWorkflow("sess-1", "wf-1", Map.of(), "key-1"))
        .thenReturn(Mono.just("exec-first"));

    StepVerifier.create(workflowService.start("sess-1", "wf-1", Map.of(), "key-1"))
        .expectNext("exec-first")
        .verifyComplete();
  }
}
