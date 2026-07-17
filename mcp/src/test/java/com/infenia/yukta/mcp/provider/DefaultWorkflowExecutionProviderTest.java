// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultWorkflowExecutionProviderTest {

  private DefaultWorkflowExecutionProvider provider;
  private WorkflowService workflowService;
  private SessionService sessionService;
  private ControlBusGateway controlBus;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    sessionService = mock(SessionService.class);
    controlBus = mock(ControlBusGateway.class);
    provider = new DefaultWorkflowExecutionProvider(workflowService, sessionService, controlBus);
  }

  @Test
  void testGetWorkflowDetails() {
    WorkflowDefinition def = mock(WorkflowDefinition.class);
    when(sessionService.getSessionWorkflow("s1", "w1")).thenReturn(Mono.just(def));

    StepVerifier.create(provider.getWorkflowDetails("s1", "w1")).expectNext(def).verifyComplete();
  }

  @Test
  void testStartWorkflow() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1")))
        .thenReturn(Mono.just(execution));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .expectNext(new WorkflowStartResult("exec-1"))
        .verifyComplete();

    verify(workflowService).validateAndStartWorkflow(eq("sess-1"), eq("wf-1"));
  }

  @Test
  void testGetWorkflowStatusReturnsProgressSnapshot() {
    WorkflowProgress progress =
        new WorkflowProgress(
            "e1",
            "sess-1",
            "w1",
            "RUNNING",
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBus.getCurrentProgress("e1")).thenReturn(progress);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1"))
        .expectNext(progress)
        .verifyComplete();
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(controlBus.getCurrentProgress("missing")).thenReturn(null);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "missing"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: missing"))
        .verify();
  }

  @Test
  void testGetWorkflowStatusOtherSessionYieldsNotFound() {
    WorkflowProgress progress =
        new WorkflowProgress(
            "e1",
            "other-session",
            "w1",
            "RUNNING",
            List.of(),
            LocalDateTime.now(ZoneId.systemDefault()),
            null);
    when(controlBus.getCurrentProgress("e1")).thenReturn(progress);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: e1"))
        .verify();
  }

  @Test
  void testGetWorkflowHistory() {
    WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary(
            "e1",
            "w1",
            "COMPLETED",
            LocalDateTime.now(ZoneId.systemDefault()),
            LocalDateTime.now(ZoneId.systemDefault()));
    when(sessionService.getSessionConfig("sess-1"))
        .thenReturn(Mono.just(Map.of("workflows", Map.of())));
    when(controlBus.getHistory("sess-1")).thenReturn(List.of(summary));

    StepVerifier.create(provider.getWorkflowHistory("sess-1"))
        .expectNext(List.of(summary))
        .verifyComplete();
  }

  @Test
  void testGetWorkflowHistorySessionNotFound() {
    when(sessionService.getSessionConfig("nope")).thenReturn(Mono.empty());

    StepVerifier.create(provider.getWorkflowHistory("nope"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Session not found: nope"))
        .verify();
  }
}
