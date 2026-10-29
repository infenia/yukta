// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.TreeControl;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowControlProviderTest {

  private static final String SESSION = "sess-1";
  private static final String EXECUTION = "exec-1";
  private static final String NODE = "node-1";

  private WorkflowControlProvider provider;
  private ControlBusGateway controlBus;
  private ExecutionResumer resumer;
  private TaskTrackerService tracker;

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    resumer = mock(ExecutionResumer.class);
    tracker = mock(TaskTrackerService.class);
    provider = new WorkflowControlProvider(controlBus, resumer, new TreeControl(tracker), tracker);
    when(controlBus.getProgress(SESSION, EXECUTION)).thenReturn(progressOwnedBy(SESSION));
  }

  private WorkflowProgress progressOwnedBy(final String sessionId) {
    return new WorkflowProgress(
        EXECUTION,
        sessionId,
        "wf-1",
        "RUNNING",
        List.of(),
        LocalDateTime.now(ZoneId.systemDefault()),
        null);
  }

  @Test
  void testPauseWorkflow() {
    when(controlBus.pauseWorkflow(EXECUTION)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.PAUSE, EXECUTION, null, null, null))
        .assertNext(
            result -> {
              org.assertj.core.api.Assertions.assertThat(result.executionId()).isEqualTo(EXECUTION);
              org.assertj.core.api.Assertions.assertThat(result.action()).isEqualTo("PAUSE");
            })
        .verifyComplete();

    verify(controlBus).pauseWorkflow(EXECUTION);
  }

  @Test
  void testResumeWorkflow() {
    when(controlBus.resumeWorkflow(EXECUTION)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.RESUME, EXECUTION, null, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).resumeWorkflow(EXECUTION);
  }

  @Test
  void testStopExecutionUsesDefaultReason() {
    when(controlBus.stopExecution(EXECUTION, "Requested via MCP", StopMode.DRAIN))
        .thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP, EXECUTION, null, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "Requested via MCP", StopMode.DRAIN);
  }

  @Test
  void testStopAllRequiresWorkflowId() {
    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP_ALL, null, null, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("workflowId is required"))
        .verify();
  }

  @Test
  void testStopWithModeKillsTheExecution() {
    when(controlBus.stopExecution(EXECUTION, "Requested via MCP", StopMode.KILL))
        .thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION,
                WorkflowControlAction.STOP,
                EXECUTION,
                null,
                null,
                null,
                false,
                StopMode.KILL))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "Requested via MCP", StopMode.KILL);
  }

  @Test
  void testStopAllWithModeCancelsTheWorkflow() {
    when(controlBus.stopWorkflow(SESSION, "wf-1", "Requested via MCP", StopMode.CANCEL))
        .thenReturn(Mono.just(List.of("e1")));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION,
                WorkflowControlAction.STOP_ALL,
                null,
                "wf-1",
                null,
                null,
                false,
                StopMode.CANCEL))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testStopAllStopsWorkflow() {
    when(controlBus.stopWorkflow(SESSION, "wf-1", "Requested via MCP", StopMode.DRAIN))
        .thenReturn(Mono.just(List.of("e1", "e2")));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP_ALL, null, "wf-1", null, null))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.resultExecutionIds())
                    .containsExactly("e1", "e2"))
        .verifyComplete();
  }

  @Test
  void testSignalSendsTheDecisionToTheNode() {
    when(controlBus.signalNode(EXECUTION, NODE, "approval", "approve", "ok"))
        .thenReturn(Mono.empty());

    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, NODE, "approval", "approve", "ok"))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.action()).isEqualTo("SIGNAL"))
        .verifyComplete();
    verify(controlBus).signalNode(EXECUTION, NODE, "approval", "approve", "ok");
  }

  @Test
  void testSignalRequiresItsArguments() {
    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, NODE, "approval", " ", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, null, NODE, "approval", "approve", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, " ", "approval", "approve", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, NODE, null, "approve", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, " ", NODE, "approval", "approve", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, null, "approval", "approve", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, NODE, " ", "approve", null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(provider.signalNode(SESSION, EXECUTION, NODE, "approval", null, null))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testRecoverReturnsNewExecutionId() {
    when(resumer.resume(EXECUTION)).thenReturn(Mono.just("exec-9"));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.RECOVER, EXECUTION, null, null, null))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.resultExecutionIds())
                    .containsExactly("exec-9"))
        .verifyComplete();
  }

  @Test
  void testRestartReturnsNewExecutionId() {
    when(controlBus.restartWorkflow(EXECUTION)).thenReturn(Mono.just("exec-2"));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.RESTART, EXECUTION, null, null, null))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.resultExecutionIds())
                    .containsExactly("exec-2"))
        .verifyComplete();
  }

  @Test
  void testRestartFromNodeRequiresFromNodeId() {
    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.RESTART_FROM_NODE, EXECUTION, null, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("fromNodeId is required"))
        .verify();
  }

  @Test
  void testRestartFromNode() {
    when(controlBus.restartFromNode(EXECUTION, NODE)).thenReturn(Mono.just("exec-3"));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.RESTART_FROM_NODE, EXECUTION, null, NODE, null))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.resultExecutionIds())
                    .containsExactly("exec-3"))
        .verifyComplete();
  }

  @Test
  void testControlWorkflowRequiresExecutionId() {
    StepVerifier.create(
            provider.controlWorkflow(SESSION, WorkflowControlAction.PAUSE, null, null, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("executionId is required"))
        .verify();
  }

  @Test
  void testControlWorkflowRejectsBlankExecutionId() {
    StepVerifier.create(
            provider.controlWorkflow(SESSION, WorkflowControlAction.PAUSE, " ", null, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("executionId is required"))
        .verify();
  }

  @Test
  void testRestartFromNodeRejectsBlankFromNodeId() {
    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.RESTART_FROM_NODE, EXECUTION, null, " ", null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("fromNodeId is required"))
        .verify();
  }

  @Test
  void testStopAllRejectsBlankWorkflowId() {
    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP_ALL, null, " ", null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("workflowId is required"))
        .verify();
  }

  @Test
  void testStopExecutionBlankReasonFallsBackToDefault() {
    when(controlBus.stopExecution(EXECUTION, "Requested via MCP", StopMode.DRAIN))
        .thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP, EXECUTION, null, null, " "))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "Requested via MCP", StopMode.DRAIN);
  }

  @Test
  void testStopExecutionUsesCustomReason() {
    when(controlBus.stopExecution(EXECUTION, "maintenance", StopMode.DRAIN))
        .thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP, EXECUTION, null, null, "maintenance"))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "maintenance", StopMode.DRAIN);
  }

  @Test
  void testOwnershipMismatchYieldsExecutionNotFound() {
    when(controlBus.getProgress(SESSION, EXECUTION)).thenReturn(null);

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.PAUSE, EXECUTION, null, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: " + EXECUTION))
        .verify();

    verify(controlBus, never()).pauseWorkflow(EXECUTION);
  }

  @Test
  void testUnknownExecutionYieldsExecutionNotFound() {
    when(controlBus.getProgress(SESSION, EXECUTION)).thenReturn(null);

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.PAUSE, EXECUTION, null, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: " + EXECUTION))
        .verify();
  }

  @Test
  void testControlNodeRejectsNullExecutionId() {
    StepVerifier.create(
            provider.controlNode(SESSION, null, NODE, NodeControlAction.PAUSE, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("executionId is required"))
        .verify();
  }

  @Test
  void testControlNodeRejectsNullNodeId() {
    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, null, NodeControlAction.PAUSE, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("nodeId is required"))
        .verify();
  }

  @Test
  void testControlNodeRejectsBlankExecutionId() {
    StepVerifier.create(
            provider.controlNode(SESSION, " ", NODE, NodeControlAction.PAUSE, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("executionId is required"))
        .verify();
  }

  @Test
  void testControlNodeRejectsBlankNodeId() {
    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, " ", NodeControlAction.PAUSE, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("nodeId is required"))
        .verify();
  }

  @Test
  void testNodePause() {
    when(controlBus.pauseNode(EXECUTION, NODE)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.PAUSE, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).pauseNode(EXECUTION, NODE);
  }

  @Test
  void testNodeResume() {
    when(controlBus.resumeNode(EXECUTION, NODE)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.RESUME, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).resumeNode(EXECUTION, NODE);
  }

  @Test
  void testNodeStopPassesImmediateAndReason() {
    when(controlBus.stopNode(EXECUTION, NODE, true, "why")).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.STOP, true, "why"))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopNode(EXECUTION, NODE, true, "why");
  }

  @Test
  void testNodeStopDefaults() {
    when(controlBus.stopNode(EXECUTION, NODE, false, "Requested via MCP")).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.STOP, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopNode(EXECUTION, NODE, false, "Requested via MCP");
  }

  @Test
  void testNodeStepActions() {
    when(controlBus.stepNode(EXECUTION, NODE)).thenReturn(Mono.empty());
    when(controlBus.enableStepMode(EXECUTION, NODE)).thenReturn(Mono.empty());
    when(controlBus.disableStepMode(EXECUTION, NODE)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.STEP, null, null))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            provider.controlNode(
                SESSION, EXECUTION, NODE, NodeControlAction.STEP_ENABLE, null, null))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            provider.controlNode(
                SESSION, EXECUTION, NODE, NodeControlAction.STEP_DISABLE, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stepNode(EXECUTION, NODE);
    verify(controlBus).enableStepMode(EXECUTION, NODE);
    verify(controlBus).disableStepMode(EXECUTION, NODE);
  }

  @Test
  void testNodeControlOwnershipMismatch() {
    when(controlBus.getProgress(SESSION, EXECUTION)).thenReturn(null);

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.PAUSE, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: " + EXECUTION))
        .verify();

    verify(controlBus, never()).pauseNode(EXECUTION, NODE);
  }

  @Test
  void testNodeSkipUnskipAndClearBreakpoint() {
    when(controlBus.skipNode(EXECUTION, NODE, true)).thenReturn(Mono.empty());
    when(controlBus.skipNode(EXECUTION, NODE, false)).thenReturn(Mono.empty());
    when(controlBus.clearBreakpoint(EXECUTION, NODE)).thenReturn(Mono.empty());

    for (final NodeControlAction action :
        List.of(
            NodeControlAction.SKIP, NodeControlAction.UNSKIP, NodeControlAction.BREAKPOINT_CLEAR)) {
      StepVerifier.create(provider.controlNode(SESSION, EXECUTION, NODE, action, null, null))
          .expectNextCount(1)
          .verifyComplete();
    }

    verify(controlBus).skipNode(EXECUTION, NODE, true);
    verify(controlBus).skipNode(EXECUTION, NODE, false);
    verify(controlBus).clearBreakpoint(EXECUTION, NODE);
  }

  @Test
  void testNodeRetryReturnsNewExecution() {
    when(controlBus.retryNode(EXECUTION, NODE)).thenReturn(Mono.just("exec-2"));

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.RETRY, null, null))
        .assertNext(
            result -> {
              org.assertj.core.api.Assertions.assertThat(result.action()).isEqualTo("RETRY");
              org.assertj.core.api.Assertions.assertThat(result.resultExecutionIds())
                  .containsExactly("exec-2");
            })
        .verifyComplete();
  }

  @Test
  void testSetBreakpoint() {
    when(controlBus.addBreakpoint(EXECUTION, NODE, "payload.status", "error"))
        .thenReturn(Mono.empty());

    StepVerifier.create(provider.addBreakpoint(SESSION, EXECUTION, NODE, "payload.status", "error"))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.action())
                    .isEqualTo("BREAKPOINT_SET"))
        .verifyComplete();
  }

  @Test
  void testSetBreakpointRequiresAllArguments() {
    for (final String[] args :
        new String[][] {
          {null, NODE, "f", "v"},
          {EXECUTION, " ", "f", "v"},
          {EXECUTION, NODE, null, "v"},
          {EXECUTION, NODE, "f", null}
        }) {
      StepVerifier.create(provider.addBreakpoint(SESSION, args[0], args[1], args[2], args[3]))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
    verify(controlBus, never()).addBreakpoint(any(), any(), any(), any());
  }

  @Test
  void testSetBreakpointRejectsBlankArguments() {
    for (final String[] args :
        new String[][] {
          {" ", NODE, "f", "v"},
          {EXECUTION, null, "f", "v"},
          {EXECUTION, NODE, " ", "v"}
        }) {
      StepVerifier.create(provider.addBreakpoint(SESSION, args[0], args[1], args[2], args[3]))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
  }

  @Test
  void testActionsThatAreNotSignalsAreRefusedByTheSignalSwitch() {
    // RETRY is answered before the signal switch, so only a direct call can reach its default.
    final Mono<?> result =
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            provider, "executeOtherNodeAction", NodeControlAction.RETRY, EXECUTION, NODE);
    StepVerifier.create(result).expectError(IllegalStateException.class).verify();
  }

  @Test
  void pendingApprovals_listsTheOpenWaitsOfTheSession() {
    final var approval =
        new com.infenia.yukta.model.control.PendingApproval(
            EXECUTION, "wf", NODE, "approval", java.time.Instant.EPOCH);
    when(controlBus.pendingApprovals(SESSION))
        .thenReturn(reactor.core.publisher.Flux.just(approval));

    StepVerifier.create(provider.pendingApprovals(SESSION))
        .assertNext(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.approvals())
                    .containsExactly(approval))
        .verifyComplete();
    StepVerifier.create(provider.pendingApprovals(" ")).verifyError(IllegalArgumentException.class);
    StepVerifier.create(provider.pendingApprovals(null))
        .verifyError(IllegalArgumentException.class);
  }

  private static WorkflowExecutionSummary run(final String id, final String status) {
    return new WorkflowExecutionSummary(
        id, "wf", status, java.time.LocalDateTime.now(), null, EXECUTION, EXECUTION, null);
  }

  @Test
  void controlWorkflow_withTree_pausesResumesAndStopsTheRunsBelowToo() {
    when(tracker.getTree(EXECUTION))
        .thenReturn(List.of(run(EXECUTION, "RUNNING"), run("child", "RUNNING")));
    when(controlBus.pauseWorkflow(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Mono.empty());
    when(controlBus.resumeWorkflow(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Mono.empty());
    when(controlBus.stopExecution(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> Mono.just(call.getArgument(0)));

    for (final WorkflowControlAction action :
        List.of(
            WorkflowControlAction.PAUSE,
            WorkflowControlAction.RESUME,
            WorkflowControlAction.STOP)) {
      StepVerifier.create(
              provider.controlWorkflow(SESSION, action, EXECUTION, null, null, null, true))
          .assertNext(result -> assertThat(result.message()).contains("with the runs below it"))
          .verifyComplete();
    }

    org.mockito.Mockito.verify(controlBus).pauseWorkflow("child");
    org.mockito.Mockito.verify(controlBus).resumeWorkflow("child");
    org.mockito.Mockito.verify(controlBus)
        .stopExecution(
            org.mockito.ArgumentMatchers.eq("child"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(StopMode.DRAIN));
  }

  @Test
  void getExecutionTree_listsTheRunsOfAnOwnedExecution() {
    when(tracker.getTree(EXECUTION)).thenReturn(List.of(run(EXECUTION, "RUNNING")));

    StepVerifier.create(provider.getExecutionTree(SESSION, EXECUTION))
        .assertNext(
            tree ->
                assertThat(tree)
                    .extracting(WorkflowExecutionSummary::executionId)
                    .containsExactly(EXECUTION))
        .verifyComplete();
  }
}
