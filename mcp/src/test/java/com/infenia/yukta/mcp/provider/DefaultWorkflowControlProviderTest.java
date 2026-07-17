// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultWorkflowControlProviderTest {

  private static final String SESSION = "sess-1";
  private static final String EXECUTION = "exec-1";
  private static final String NODE = "node-1";

  private DefaultWorkflowControlProvider provider;
  private ControlBusGateway controlBus;

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    provider = new DefaultWorkflowControlProvider(controlBus);
    when(controlBus.getCurrentProgress(EXECUTION)).thenReturn(progressOwnedBy(SESSION));
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
    when(controlBus.stopExecution(EXECUTION, "Requested via MCP")).thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP, EXECUTION, null, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "Requested via MCP");
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
  void testStopAllStopsWorkflow() {
    when(controlBus.stopWorkflow(SESSION, "wf-1", "Requested via MCP"))
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
    when(controlBus.stopExecution(EXECUTION, "Requested via MCP")).thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP, EXECUTION, null, null, " "))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "Requested via MCP");
  }

  @Test
  void testStopExecutionUsesCustomReason() {
    when(controlBus.stopExecution(EXECUTION, "maintenance")).thenReturn(Mono.just(EXECUTION));

    StepVerifier.create(
            provider.controlWorkflow(
                SESSION, WorkflowControlAction.STOP, EXECUTION, null, null, "maintenance"))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).stopExecution(EXECUTION, "maintenance");
  }

  @Test
  void testOwnershipMismatchYieldsExecutionNotFound() {
    when(controlBus.getCurrentProgress(EXECUTION)).thenReturn(progressOwnedBy("other-session"));

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
    when(controlBus.getCurrentProgress(EXECUTION)).thenReturn(null);

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
  void testNodeSkip() {
    when(controlBus.skipNode(EXECUTION, NODE, true)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.SKIP, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).skipNode(EXECUTION, NODE, true);
  }

  @Test
  void testNodeUnskip() {
    when(controlBus.skipNode(EXECUTION, NODE, false)).thenReturn(Mono.empty());

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.UNSKIP, null, null))
        .expectNextCount(1)
        .verifyComplete();

    verify(controlBus).skipNode(EXECUTION, NODE, false);
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
    when(controlBus.getCurrentProgress(EXECUTION)).thenReturn(progressOwnedBy("other-session"));

    StepVerifier.create(
            provider.controlNode(SESSION, EXECUTION, NODE, NodeControlAction.PAUSE, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: " + EXECUTION))
        .verify();

    verify(controlBus, never()).pauseNode(EXECUTION, NODE);
  }
}
