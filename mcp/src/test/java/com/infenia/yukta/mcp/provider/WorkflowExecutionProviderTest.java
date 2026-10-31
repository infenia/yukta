// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import com.infenia.yukta.service.workflow.WorkflowService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowExecutionProviderTest {

  private WorkflowExecutionProvider provider;
  private WorkflowService workflowService;
  private SessionService sessionService;
  private ControlBusGateway controlBus;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    sessionService = mock(SessionService.class);
    controlBus = mock(ControlBusGateway.class);
    provider =
        new WorkflowExecutionProvider(
            workflowService,
            sessionService,
            controlBus,
            new ConfigRedactor(mock(PluginRegistry.class)),
            java.time.Duration.ofSeconds(30));
  }

  @Test
  void testGetWorkflowDetails() {
    WorkflowDefinition def =
        new WorkflowDefinition(
            "w1",
            "d",
            List.of(
                new WorkflowDefinition.Node(
                    "n1", "HTTP_REQUEST", Map.of("url", "http://x", "apiKey", "k-123"))),
            List.of());
    when(sessionService.getSessionWorkflow("s1", "w1")).thenReturn(Mono.just(def));

    StepVerifier.create(provider.getWorkflowDetails("s1", "w1"))
        .assertNext(
            details ->
                org.assertj.core.api.Assertions.assertThat(details.nodes().getFirst().config())
                    .containsEntry("url", "http://x")
                    .containsEntry("apiKey", "******"))
        .verifyComplete();
  }

  @Test
  void testGetWorkflowDetailsOfUnknownSessionOrWorkflowIsAnActionableError() {
    when(sessionService.getSessionWorkflow("s1", "typo")).thenReturn(Mono.empty());

    StepVerifier.create(provider.getWorkflowDetails("s1", "typo"))
        .expectErrorMessage(
            "Workflow not found: typo in session s1. Use list_sessions to see the sessions and"
                + " get_session_details for the workflows of one.")
        .verify();
  }

  @Test
  void testStartWorkflow() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap()))
        .thenReturn(Mono.just(execution));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .expectNext(new WorkflowStartResult("exec-1", false))
        .verifyComplete();

    verify(workflowService).validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap());
  }

  @Test
  void testAnAgentStartRecordsWhoStartedIt() {
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("exec-1", Mono.empty())));

    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-key"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("exec-2", Mono.empty())));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .expectNextCount(1)
        .verifyComplete();
    // a different workflow, because a keyless repeat of the same one is merged with the first
    StepVerifier.create(
            provider
                .startWorkflow("sess-1", "wf-key")
                .contextWrite(
                    reactor.util.context.Context.of(
                        CallerIdentity.CONTEXT_KEY,
                        new ApiKeys.Match("claude", ApiRole.OPERATOR, java.util.Set.of()))))
        .expectNextCount(1)
        .verifyComplete();

    final var payloads = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(workflowService, org.mockito.Mockito.atLeast(1))
        .validateAndStartWorkflow(
            eq("sess-1"), org.mockito.ArgumentMatchers.anyString(), payloads.capture());
    assertThat(payloads.getAllValues())
        .extracting(payload -> payload.get(ExecutionLogEvent.INITIATOR_KEY))
        .contains("mcp", "mcp · claude");
  }

  @Test
  void testStartWorkflowWithKeyPassesItToTheService() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap(), eq("k-1")))
        .thenReturn(Mono.just(execution));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1", " k-1 "))
        .expectNext(new WorkflowStartResult("exec-1", false))
        .verifyComplete();
  }

  @Test
  void testKeylessRepeatReturnsTheFirstExecution() {
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("exec-1", Mono.empty())));
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-2"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("exec-2", Mono.empty())));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .expectNext(new WorkflowStartResult("exec-1", false))
        .verifyComplete();
    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .expectNext(new WorkflowStartResult("exec-1", true))
        .verifyComplete();
    // Another workflow is not merged.
    StepVerifier.create(provider.startWorkflow("sess-1", "wf-2"))
        .expectNext(new WorkflowStartResult("exec-2", false))
        .verifyComplete();
  }

  @Test
  void testKeylessRepeatIsMarkedReusedButKeyedStartIsNot() {
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("exec-1", Mono.empty())));
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap(), eq("fresh")))
        .thenReturn(Mono.just(new WorkflowExecution("exec-2", Mono.empty())));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .assertNext(result -> assertThat(result.reusedExecution()).isFalse())
        .verifyComplete();
    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .assertNext(result -> assertThat(result.reusedExecution()).isTrue())
        .verifyComplete();
    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1", "fresh"))
        .assertNext(
            result -> {
              assertThat(result.executionId()).isEqualTo("exec-2");
              assertThat(result.reusedExecution()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void testStartWorkflowWithBlankKeyStartsWithoutOne() {
    when(workflowService.validateAndStartWorkflow(eq("sess-1"), eq("wf-1"), anyMap()))
        .thenReturn(Mono.just(new WorkflowExecution("exec-2", Mono.empty())));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1", "  "))
        .expectNext(new WorkflowStartResult("exec-2", false))
        .verifyComplete();
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
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(progress);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1"))
        .expectNext(progress)
        .verifyComplete();
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(controlBus.getProgress("sess-1", "missing")).thenReturn(null);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "missing"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: missing"))
        .verify();
  }

  @Test
  void testGetWorkflowStatusOtherSessionYieldsNotFound() {
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(null);

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
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "sess-1", "Test Session", "desc", "initiator", Map.of(), "/path", Map.of());
    when(sessionService.getSessionConfig("sess-1")).thenReturn(Mono.just(config));
    when(controlBus.getHistory("sess-1")).thenReturn(Mono.just(List.of(summary)));

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
