// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for {@link WorkflowService}. */
@ExtendWith(MockitoExtension.class)
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
    workflowService = new WorkflowService(controlBus);
  }

  @Test
  void testValidateAndStartWorkflowSuccess() {
    final String sessionId = "sess-123";
    final String workflowId = "wf-456";
    final String executionId = "exec-789";

    when(controlBus.startWorkflow(sessionId, workflowId)).thenReturn(Mono.just(executionId));

    StepVerifier.create(workflowService.validateAndStartWorkflow(sessionId, workflowId))
        .assertNext(
            execution -> {
              assertThat(execution.executionId()).isEqualTo(executionId);
              assertThat(execution.result()).isNotNull();
            })
        .verifyComplete();
  }

  @Test
  void testValidateAndStartWorkflowNotFound() {
    final String sessionId = "sess-missing";
    final String workflowId = "wf-missing";

    when(controlBus.startWorkflow(sessionId, workflowId))
        .thenReturn(
            Mono.error(
                new IllegalArgumentException(
                    "Workflow not found for session: " + sessionId + ", workflow: " + workflowId)));

    StepVerifier.create(workflowService.validateAndStartWorkflow(sessionId, workflowId))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Workflow not found"))
        .verify();
  }

  @Test
  void testValidateAndStartWorkflowReturnsExecutionWithId() {
    final String sessionId = "sess-exec";
    final String workflowId = "wf-exec";
    final String executionId = "exec-001";

    when(controlBus.startWorkflow(sessionId, workflowId)).thenReturn(Mono.just(executionId));

    final WorkflowExecution execution =
        workflowService.validateAndStartWorkflow(sessionId, workflowId).block();

    assertThat(execution).isNotNull();
    assertThat(execution.executionId()).isEqualTo(executionId);
  }
}
