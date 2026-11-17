// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.control.gateway.WorkflowService;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import com.infenia.yukta.testing.Fixtures;
import java.time.Duration;
import java.time.Instant;
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
        Fixtures.workflow(
            "w1",
            "d",
            List.of(
                Fixtures.node("n1", "HTTP_REQUEST", Map.of("url", "http://x", "apiKey", "k-123"))),
            List.of());
    when(sessionService.getSessionWorkflow("s1", "w1")).thenReturn(Mono.just(def));

    StepVerifier.create(provider.getWorkflowDetails("s1", "w1"))
        .assertNext(details ->
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
    final String execution = "exec-1";
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), any()))
        .thenReturn(Mono.just(execution));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .expectNext(new WorkflowStartResult("exec-1", false))
        .verifyComplete();

    verify(workflowService).start(eq("sess-1"), eq("wf-1"), anyMap(), any());
  }

  @Test
  void testAnAgentStartRecordsWhoStartedIt() {
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), any()))
        .thenReturn(Mono.just("exec-1"));

    when(workflowService.start(eq("sess-1"), eq("wf-key"), anyMap(), any()))
        .thenReturn(Mono.just("exec-2"));

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
        .start(eq("sess-1"), org.mockito.ArgumentMatchers.anyString(), payloads.capture(), any());
    assertThat(payloads.getAllValues())
        .extracting(payload -> payload.get(ExecutionLogEvent.INITIATOR_KEY))
        .contains("mcp", "mcp · claude");
  }

  @Test
  void testStartWorkflowWithKeyPassesItToTheService() {
    final String execution = "exec-1";
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), eq("k-1")))
        .thenReturn(Mono.just(execution));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1", " k-1 "))
        .expectNext(new WorkflowStartResult("exec-1", false))
        .verifyComplete();
  }

  @Test
  void testKeylessRepeatReturnsTheFirstExecution() {
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), any()))
        .thenReturn(Mono.just("exec-1"));
    when(workflowService.start(eq("sess-1"), eq("wf-2"), anyMap(), any()))
        .thenReturn(Mono.just("exec-2"));

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
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), any()))
        .thenReturn(Mono.just("exec-1"));
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), eq("fresh")))
        .thenReturn(Mono.just("exec-2"));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .assertNext(result -> assertThat(result.reusedExecution()).isFalse())
        .verifyComplete();
    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1"))
        .assertNext(result -> assertThat(result.reusedExecution()).isTrue())
        .verifyComplete();
    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1", "fresh"))
        .assertNext(result -> {
          assertThat(result.executionId()).isEqualTo("exec-2");
          assertThat(result.reusedExecution()).isFalse();
        })
        .verifyComplete();
  }

  @Test
  void testStartWorkflowWithBlankKeyStartsWithoutOne() {
    when(workflowService.start(eq("sess-1"), eq("wf-1"), anyMap(), any()))
        .thenReturn(Mono.just("exec-2"));

    StepVerifier.create(provider.startWorkflow("sess-1", "wf-1", "  "))
        .expectNext(new WorkflowStartResult("exec-2", false))
        .verifyComplete();
  }

  @Test
  void testGetWorkflowStatusReturnsProgressSnapshot() {
    WorkflowProgress progress =
        Fixtures.workflowProgress(
            "e1", "sess-1", "w1", TaskStatus.RUNNING, List.of(), Instant.now(), null);
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(progress);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1"))
        .expectNext(progress)
        .verifyComplete();
  }

  private static WorkflowProgress progress(final TaskStatus status) {
    return Fixtures.workflowProgress("e1", "sess-1", "w1", status, List.of(), Instant.EPOCH, null);
  }

  @Test
  void testWaitReturnsTheFirstChangedSnapshot() {
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(progress(TaskStatus.RUNNING));
    final WorkflowProgress done = progress(TaskStatus.SUCCESS);
    when(controlBus.watchExecution("e1", false))
        .thenReturn(reactor.core.publisher.Flux.just(progress(TaskStatus.RUNNING), done));

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1", 10))
        .expectNext(done)
        .verifyComplete();
  }

  @Test
  void testWaitWithoutChangeAnswersWithTheSnapshotWhenTimeIsUp() {
    final WorkflowProgress running = progress(TaskStatus.RUNNING);
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(running);
    when(controlBus.watchExecution("e1", false)).thenReturn(reactor.core.publisher.Flux.never());

    StepVerifier.withVirtualTime(() -> provider.getWorkflowStatus("sess-1", "e1", 300))
        .expectSubscription()
        .expectNoEvent(Duration.ofSeconds(WorkflowExecutionProvider.MAX_WAIT_SECONDS - 1))
        .thenAwait(Duration.ofSeconds(1))
        .expectNext(running)
        .verifyComplete();
  }

  @Test
  void testWaitSeesNodeStatusChange() {
    final TaskProgress node =
        new TaskProgress("n1", "mod", TaskStatus.RUNNING, null, null, Map.of(), null);
    final TaskProgress nodeDone =
        new TaskProgress("n1", "mod", TaskStatus.SUCCESS, null, null, Map.of(), null);
    final WorkflowProgress before =
        Fixtures.workflowProgress(
            "e1", "sess-1", "w1", TaskStatus.RUNNING, List.of(node), Instant.EPOCH, null);
    final WorkflowProgress after =
        Fixtures.workflowProgress(
            "e1", "sess-1", "w1", TaskStatus.RUNNING, List.of(nodeDone), Instant.EPOCH, null);
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(before);
    when(controlBus.watchExecution("e1", false))
        .thenReturn(reactor.core.publisher.Flux.just(after));

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1", 10))
        .expectNext(after)
        .verifyComplete();
  }

  @Test
  void testWaitWithoutStatusKeepsWaiting() {
    final WorkflowProgress unknown = progress(null);
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(unknown);
    when(controlBus.watchExecution("e1", false)).thenReturn(reactor.core.publisher.Flux.never());

    StepVerifier.withVirtualTime(() -> provider.getWorkflowStatus("sess-1", "e1", 5))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(5))
        .expectNext(unknown)
        .verifyComplete();
  }

  @Test
  void testWaitWithNoTimeAnswersWithTheSnapshotAtOnce() {
    final WorkflowProgress running = progress(TaskStatus.RUNNING);
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(running);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1", null))
        .expectNext(running)
        .verifyComplete();
    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1", 0))
        .expectNext(running)
        .verifyComplete();
    org.mockito.Mockito.verify(controlBus, org.mockito.Mockito.never()).watchExecution("e1", false);
  }

  @Test
  void testWaitOnFinishedRunAnswersAtOnce() {
    final WorkflowProgress done = progress(TaskStatus.SUCCESS);
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(done);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1", 10))
        .expectNext(done)
        .verifyComplete();
    org.mockito.Mockito.verify(controlBus, org.mockito.Mockito.never()).watchExecution("e1", false);
  }

  @Test
  void testGetWorkflowStatusNotFound() {
    when(controlBus.getProgress("sess-1", "missing")).thenReturn(null);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "missing"))
        .expectErrorMatches(error ->
            error instanceof IllegalArgumentException
                && error.getMessage().contains("Execution not found: missing"))
        .verify();
  }

  @Test
  void testGetWorkflowStatusOtherSessionYieldsNotFound() {
    when(controlBus.getProgress("sess-1", "e1")).thenReturn(null);

    StepVerifier.create(provider.getWorkflowStatus("sess-1", "e1"))
        .expectErrorMatches(error ->
            error instanceof IllegalArgumentException
                && error.getMessage().contains("Execution not found: e1"))
        .verify();
  }

  @Test
  void testGetWorkflowHistory() {
    WorkflowExecutionSummary summary =
        Fixtures.summary("e1", "w1", TaskStatus.SUCCESS, Instant.now(), Instant.now());
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
        .expectErrorMatches(error ->
            error instanceof IllegalArgumentException
                && error.getMessage().contains("Session not found: nope"))
        .verify();
  }
}
