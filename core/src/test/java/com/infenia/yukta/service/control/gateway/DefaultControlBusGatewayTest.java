/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.control.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.factory.ExecutionControlFactory;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.CouplingBetweenObjects",
  "PMD.CyclomaticComplexity",
  "PMD.ExcessiveImports",
  "PMD.LawOfDemeter",
  "PMD.LinguisticNaming",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports"
})
@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
class DefaultControlBusGatewayTest {

  @Mock private ControlBusService controlBusService;
  @Mock private DefaultTaskTrackerService taskTracker;
  @Mock private ExecutionControlRegistry executionControlRegistry;
  @Mock private WorkflowOrchestrator orchestrator;
  @Mock private WorkflowDefinitionStore workflowDefinitionStore;
  @Mock private PreparedWorkflowCache preparedWorkflowCache;

  private DefaultControlBusGateway gateway;

  @BeforeEach
  void setUp() {
    gateway =
        new DefaultControlBusGateway(
            controlBusService,
            taskTracker,
            executionControlRegistry,
            orchestrator,
            workflowDefinitionStore,
            preparedWorkflowCache);
  }

  // --- Plugin & Message Management Tests ---

  @Test
  void emit_validMessage_delegatesToControlBusService() {
    // Given
    final Message<String> signal = DefaultMessage.create(null, "test-payload");
    when(controlBusService.emit(signal)).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.emit(signal);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).emit(signal);
  }

  @Test
  void registerPlugin_validInputs_delegatesToControlBusService() {
    // Given
    final String workflowId = "workflow-1";
    final String nodeId = "node-1";
    final Plugin plugin = mock(Plugin.class);

    // When
    gateway.registerPlugin(workflowId, nodeId, plugin);

    // Then
    verify(controlBusService).registerPlugin(workflowId, nodeId, plugin);
  }

  @Test
  void unregisterPlugin_validInputs_delegatesToControlBusService() {
    // Given
    final String workflowId = "workflow-1";
    final String nodeId = "node-1";

    // When
    gateway.unregisterPlugin(workflowId, nodeId);

    // Then
    verify(controlBusService).unregisterPlugin(workflowId, nodeId);
  }

  @Test
  void sendCommand_validInputs_delegatesToControlBusService() {
    // Given
    final String workflowId = "workflow-1";
    final String nodeId = "node-1";
    final Message<?> command = DefaultMessage.create(null, "command-payload");
    final Message<?> response = DefaultMessage.create(null, "response-payload");
    when(controlBusService.sendCommand(workflowId, nodeId, command))
        .thenReturn(Mono.just(response));

    // When
    final Mono<Message<?>> result = gateway.sendCommand(workflowId, nodeId, command);

    // Then
    StepVerifier.create(result).expectNext(response).verifyComplete();
    verify(controlBusService).sendCommand(workflowId, nodeId, command);
  }

  @Test
  void compileAndCacheWorkflow_validInputs_delegatesToControlBusService() {
    // Given
    final String sessionId = "session-1";
    final WorkflowDefinition definition =
        new WorkflowDefinition("workflow-1", "description", List.of(), List.of());
    when(controlBusService.compileAndCacheWorkflow(sessionId, definition)).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.compileAndCacheWorkflow(sessionId, definition);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).compileAndCacheWorkflow(sessionId, definition);
  }

  /**
   * Stubs an {@link ExecutionControl} registered under {@code executionId} whose {@code
   * nodePauseValves()} contains {@code nodeId}, satisfying the node-existence check shared by the
   * pause/resume/stop/skip node operations.
   */
  private ExecutionControl stubNodeExists(final String executionId, final String nodeId) {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.nodePauseValves()).thenReturn(Map.of(nodeId, mock(ReactiveControlValve.class)));
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    return control;
  }

  /** Stubs an {@link ExecutionControl} registered under {@code executionId} with no nodes. */
  private void stubNoNodes(final String executionId) {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.nodePauseValves()).thenReturn(Map.of());
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
  }

  // --- Execution Control Command Tests ---

  @Test
  void pauseWorkflow_validExecutionId_emitsPauseCommand() {
    // Given
    final String executionId = "exec-1";
    final ExecutionControl control = mock(ExecutionControl.class);
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(PauseWorkflowCommand.class);
    final PauseWorkflowCommand cmd = (PauseWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(emittedMessage.getPriority()).isEqualTo(100);
    assertThat(emittedMessage.getSourceNodeId()).isEqualTo("CONTROL_BUS");
    assertThat(emittedMessage.isControlMessage()).isTrue();
  }

  @Test
  void resumeWorkflow_validExecutionId_emitsResumeCommand() {
    // Given
    final String executionId = "exec-2";
    final ExecutionControl control = mock(ExecutionControl.class);
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.resumeWorkflow(executionId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(ResumeWorkflowCommand.class);
    final ResumeWorkflowCommand cmd = (ResumeWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
  }

  @Test
  void pauseWorkflow_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-pause-not-found";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void resumeWorkflow_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-resume-not-found";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.resumeWorkflow(executionId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void pauseNode_validInputs_emitsPauseNodeCommand() {
    // Given
    final String executionId = "exec-3";
    final String nodeId = "node-2";
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.pauseNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(PauseNodeCommand.class);
    final PauseNodeCommand cmd = (PauseNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void resumeNode_validInputs_emitsResumeNodeCommand() {
    // Given
    final String executionId = "exec-4";
    final String nodeId = "node-3";
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.resumeNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(ResumeNodeCommand.class);
    final ResumeNodeCommand cmd = (ResumeNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void pauseNode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-pause-node-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.pauseNode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void resumeNode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-resume-node-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.resumeNode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void pauseNode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-6";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.pauseNode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void resumeNode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-7";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.resumeNode(executionId, nodeId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void pauseNodeAndResumeNode_realExecutionControl_validatesAgainstActualNodePauseValves() {
    // Given - a real ExecutionControl built by the real factory, registered in a real registry,
    // so the node-existence check exercises the actual nodePauseValves() population logic
    // instead of a hand-shaped mock.
    final WorkflowNode node1 = new WorkflowNode("n1", "t", null);
    final WorkflowNode node2 = new WorkflowNode("n2", "t", null);
    final PreparedWorkflow prepared =
        new PreparedWorkflow(
            List.of(new WorkflowEdge("n1", "n2", null)),
            Map.of("n1", List.of(node2), "n2", List.of()),
            Map.of("n1", List.of(), "n2", List.of(node1)),
            Map.of(),
            List.of(node1, node2),
            (id, payload) -> Mono.empty());
    final String executionId = "exec-real";
    final ExecutionControl control =
        new ExecutionControlFactory()
            .create("session-1", "workflow-1", executionId, prepared, Map.of());
    final ExecutionControlRegistry realRegistry =
        new ExecutionControlRegistry(new InMemoryExecutionControlStore());
    realRegistry.register(control);
    final DefaultControlBusGateway realGateway =
        new DefaultControlBusGateway(
            controlBusService,
            taskTracker,
            realRegistry,
            orchestrator,
            workflowDefinitionStore,
            preparedWorkflowCache);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When / Then - a real, existing node succeeds
    StepVerifier.create(realGateway.pauseNode(executionId, "n1")).verifyComplete();
    StepVerifier.create(realGateway.resumeNode(executionId, "n2")).verifyComplete();
    verify(controlBusService, times(2)).emit(any());

    // When / Then - a node absent from the real topological order is rejected
    StepVerifier.create(realGateway.pauseNode(executionId, "unknown-node"))
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains("unknown-node"))
        .verify();
  }

  @Test
  void stopNode_normalStop_emitsStopCommandWithElevatedPriority() {
    // Given
    final String executionId = "exec-5";
    final String nodeId = "node-4";
    final boolean immediate = false;
    final String reason = "test reason";
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.stopNode(executionId, nodeId, immediate, reason);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(StopNodeCommand.class);
    final StopNodeCommand cmd = (StopNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
    assertThat(cmd.immediate()).isEqualTo(immediate);
    assertThat(cmd.reason()).isEqualTo(reason);
    assertThat(emittedMessage.getPriority()).isEqualTo(110); // CONTROL_COMMAND_PRIORITY + 10
  }

  @Test
  void stopNode_immediateStop_emitsStopCommandWithImmediateFlag() {
    // Given
    final String executionId = "exec-6";
    final String nodeId = "node-5";
    final boolean immediate = true;
    final String reason = "immediate stop";
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.stopNode(executionId, nodeId, immediate, reason);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    final StopNodeCommand cmd = (StopNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.immediate()).isTrue();
  }

  // --- stopWorkflow Tests ---

  @Test
  void stopWorkflow_activeExecutionFound_stopsAllAndReturnsExecutionIds() {
    // Given
    final String sessionId = "sess-stop-wf";
    final String workflowId = "wf-stop";
    final String executionId = "exec-to-stop";
    final String reason = "User requested stop";

    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId))
        .thenReturn(List.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<List<String>> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(
            stoppedIds -> {
              assertThat(stoppedIds).hasSize(1).contains(executionId);
            })
        .verifyComplete();

    verify(executionControlRegistry).findAllActiveByWorkflow(sessionId, workflowId);
    verify(controlBusService).emit(any());
  }

  @Test
  void stopWorkflow_noActiveExecution_throwsIllegalArgumentException() {
    // Given
    final String sessionId = "sess-no-exec";
    final String workflowId = "wf-no-exec";
    final String reason = "stop";
    when(executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId))
        .thenReturn(List.of());

    // When
    final Mono<List<String>> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("No active execution found")
                    && err.getMessage().contains(sessionId)
                    && err.getMessage().contains(workflowId))
        .verify();
  }

  @Test
  void stopExecution_validInputs_emitsStopCommand() {
    // Given
    final String executionId = "exec-to-stop";
    final String reason = "User requested stop";

    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.stopExecution(executionId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(stoppedId -> assertThat(stoppedId).isEqualTo(executionId))
        .verifyComplete();

    verify(executionControlRegistry).findByExecutionId(executionId);
    verify(controlBusService).emit(any());
  }

  @Test
  void stopExecution_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-not-found";
    final String reason = "stop";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<String> result = gateway.stopExecution(executionId, reason);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
  }

  @Test
  void stopWorkflow_correctStopWorkflowCommandBuilt_withElevatedPriority() {
    // Given
    final String sessionId = "sess-cmd-build";
    final String workflowId = "wf-cmd-build";
    final String executionId = "exec-cmd";
    final String reason = "priority test";

    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId))
        .thenReturn(List.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<List<String>> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(ids -> assertThat(ids).contains(executionId))
        .verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();

    assertThat(emittedMessage.getPayload()).isInstanceOf(StopWorkflowCommand.class);
    final StopWorkflowCommand cmd = (StopWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.reason()).isEqualTo(reason);
    assertThat(emittedMessage.getPriority()).isEqualTo(120); // CONTROL_COMMAND_PRIORITY + 20
    assertThat(emittedMessage.getSourceNodeId()).isEqualTo("CONTROL_BUS");
    assertThat(emittedMessage.isControlMessage()).isTrue();
  }

  @Test
  void stopWorkflow_multipleActiveExecutions_stopsAllWithReasons() {
    // Given
    final String sessionId = "sess-reason";
    final String workflowId = "wf-reason";
    final String executionId1 = "exec-1";
    final String executionId2 = "exec-2";
    final String reason = "Timeout exceeded";

    final ExecutionControl control1 = mock(ExecutionControl.class);
    when(control1.executionId()).thenReturn(executionId1);

    final ExecutionControl control2 = mock(ExecutionControl.class);
    when(control2.executionId()).thenReturn(executionId2);

    when(executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId))
        .thenReturn(List.of(control1, control2));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<List<String>> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(
            ids -> {
              assertThat(ids).hasSize(2).contains(executionId1, executionId2);
            })
        .verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService, times(2)).emit(captor.capture());
    final List<Message<?>> allMessages = captor.getAllValues();
    for (final Message<?> msg : allMessages) {
      final StopWorkflowCommand cmd = (StopWorkflowCommand) msg.getPayload();
      assertThat(cmd.reason()).isEqualTo(reason);
    }
  }

  @Test
  void stopWorkflow_executeCommandFails_propagatesError() {
    // Given
    final String sessionId = "sess-fail";
    final String workflowId = "wf-fail";
    final String executionId = "exec-fail";
    final String reason = "should fail";

    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId))
        .thenReturn(List.of(control));

    final RuntimeException testError = new RuntimeException("Command execution failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<List<String>> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stopWorkflow_sessionAndWorkflowIdsPassed_queriesRegistryCorrectly() {
    // Given
    final String sessionId = "sess-registry";
    final String workflowId = "wf-registry";
    final String executionId = "exec-registry";
    final String reason = "registry test";

    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId))
        .thenReturn(List.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<List<String>> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result).assertNext(ids -> assertThat(ids).isNotNull()).verifyComplete();

    verify(executionControlRegistry).findAllActiveByWorkflow(sessionId, workflowId);
  }

  @Test
  void skipNode_skipTrue_emitsSkipCommand() {
    // Given
    final String executionId = "exec-7";
    final String nodeId = "node-6";
    final boolean skip = true;
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.skipNode(executionId, nodeId, skip);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(SkipNodeCommand.class);
    final SkipNodeCommand cmd = (SkipNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.skip()).isTrue();
  }

  @Test
  void skipNode_skipFalse_emitsUnskipCommand() {
    // Given
    final String executionId = "exec-8";
    final String nodeId = "node-7";
    final boolean skip = false;
    stubNodeExists(executionId, nodeId);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.skipNode(executionId, nodeId, skip);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    final SkipNodeCommand cmd = (SkipNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.skip()).isFalse();
  }

  @Test
  void enableStepMode_validInputs_emitsEnableStepModeCommand() {
    // Given
    final String executionId = "exec-9";
    final String nodeId = "node-8";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.enableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(EnableStepModeCommand.class);
    final EnableStepModeCommand cmd = (EnableStepModeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void disableStepMode_validInputs_emitsDisableStepModeCommand() {
    // Given
    final String executionId = "exec-10";
    final String nodeId = "node-9";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.disableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(DisableStepModeCommand.class);
    final DisableStepModeCommand cmd = (DisableStepModeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void stepNode_validInputs_emitsStepNodeCommand() {
    // Given
    final String executionId = "exec-11";
    final String nodeId = "node-10";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.stepNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(StepNodeCommand.class);
    final StepNodeCommand cmd = (StepNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void restartWorkflow_validExecutionId_generatesNewExecutionId() {
    // Given
    final String executionId = "exec-12";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.restartWorkflow(executionId);

    // Then
    StepVerifier.create(result)
        .assertNext(
            newExecId -> {
              assertThat(newExecId).isNotNull();
              assertThat(newExecId).isNotEqualTo(executionId);
              // Verify it's a valid UUID format - should not throw exception
              try {
                UUID.fromString(newExecId);
              } catch (final IllegalArgumentException e) {
                throw new AssertionError("Not a valid UUID: " + newExecId, e);
              }
            })
        .verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(RestartCommand.class);
    final RestartCommand cmd = (RestartCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(emittedMessage.getPriority()).isEqualTo(120); // CONTROL_COMMAND_PRIORITY + 20
  }

  @Test
  void restartFromNode_validInputs_generatesNewExecutionId() {
    // Given
    final String executionId = "exec-13";
    final String fromNodeId = "node-11";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.restartFromNode(executionId, fromNodeId);

    // Then
    StepVerifier.create(result)
        .assertNext(
            newExecId -> {
              assertThat(newExecId).isNotNull();
              assertThat(newExecId).isNotEqualTo(executionId);
              // Verify it's a valid UUID format - should not throw exception
              try {
                UUID.fromString(newExecId);
              } catch (final IllegalArgumentException e) {
                throw new AssertionError("Not a valid UUID: " + newExecId, e);
              }
            })
        .verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(RestartFromNodeCommand.class);
    final RestartFromNodeCommand cmd = (RestartFromNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.fromNodeId()).isEqualTo(fromNodeId);
    assertThat(emittedMessage.getPriority()).isEqualTo(120); // CONTROL_COMMAND_PRIORITY + 20
  }

  // --- Observability Tests ---

  @Test
  void watchExecution_validExecutionId_delegatesToTaskTracker() {
    // Given
    final String executionId = "exec-14";
    final WorkflowProgress progress =
        new WorkflowProgress("exec", "sess", "wf", "status", List.of(), null, null);
    when(taskTracker.getStatusStream(executionId, true)).thenReturn(Flux.just(progress));

    // When
    final Flux<WorkflowProgress> result = gateway.watchExecution(executionId, true);

    // Then
    StepVerifier.create(result).expectNext(progress).verifyComplete();
    verify(taskTracker).getStatusStream(executionId, true);
  }

  @Test
  void watchLogs_validExecutionId_delegatesToTaskTracker() {
    // Given
    final String sessionId = "session-1";
    final String executionId = "exec-15";
    final String logLine = "test log";
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.just(logLine));

    // When
    final Flux<String> result = gateway.watchLogs(sessionId, executionId);

    // Then
    StepVerifier.create(result).expectNext(logLine).verifyComplete();
    verify(taskTracker).getLogStream(executionId);
  }

  @Test
  void getCurrentProgress_validExecutionId_delegatesToTaskTracker() {
    // Given
    final String executionId = "exec-16";
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(taskTracker.getProgressByExecutionId(executionId)).thenReturn(progress);

    // When
    final WorkflowProgress result = gateway.getCurrentProgress(executionId);

    // Then
    assertThat(result).isEqualTo(progress);
    verify(taskTracker).getProgressByExecutionId(executionId);
  }

  @Test
  void getHistory_validSessionId_delegatesToTaskTracker() {
    // Given
    final String sessionId = "session-2";
    final WorkflowExecutionSummary summary = mock(WorkflowExecutionSummary.class);
    final List<WorkflowExecutionSummary> history = List.of(summary);
    when(taskTracker.getHistory(sessionId)).thenReturn(history);

    // When
    final List<WorkflowExecutionSummary> result = gateway.getHistory(sessionId);

    // Then
    assertThat(result).isEqualTo(history);
    verify(taskTracker).getHistory(sessionId);
  }

  // --- State Query Tests ---

  @Test
  void getLastHeartbeat_validInputs_delegatesToControlBusService() {
    // Given
    final String workflowId = "workflow-2";
    final String nodeId = "node-12";
    when(controlBusService.getLastHeartbeat(workflowId, nodeId)).thenReturn(null);

    // When
    final Message<?> result = gateway.getLastHeartbeat(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
    verify(controlBusService).getLastHeartbeat(workflowId, nodeId);
  }

  @Test
  void getLastStatistics_validInputs_delegatesToControlBusService() {
    // Given
    final String workflowId = "workflow-3";
    final String nodeId = "node-13";
    when(controlBusService.getLastStatistics(workflowId, nodeId)).thenReturn(null);

    // When
    final Message<?> result = gateway.getLastStatistics(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
    verify(controlBusService).getLastStatistics(workflowId, nodeId);
  }

  @Test
  void getActiveNodes_withWorkflowId_delegatesToControlBusService() {
    // Given
    final String workflowId = "workflow-4";
    final List<String> activeNodes = List.of("node-14", "node-15");
    when(controlBusService.getActiveNodes(workflowId)).thenReturn(activeNodes);

    // When
    final List<String> result = gateway.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEqualTo(activeNodes);
    verify(controlBusService).getActiveNodes(workflowId);
  }

  @Test
  void getActiveNodes_noWorkflowId_delegatesToControlBusService() {
    // Given
    final List<String> allActiveNodes = List.of("node-16", "node-17", "node-18");
    when(controlBusService.getActiveNodes()).thenReturn(allActiveNodes);

    // When
    final List<String> result = gateway.getActiveNodes();

    // Then
    assertThat(result).isEqualTo(allActiveNodes);
    verify(controlBusService).getActiveNodes();
  }

  @Test
  void executeCommand_validCommand_delegatesToEmit() {
    // Given
    final PauseWorkflowCommand command = new PauseWorkflowCommand("exec-22");
    final Message<PauseWorkflowCommand> cmdMessage = DefaultMessage.create(null, command);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.executeCommand(cmdMessage);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).emit(any());
  }

  @Test
  void buildCommand_createsMessageWithCorrectProperties() {
    // This test verifies the buildCommand method is executed
    // by testing that control commands create proper messages
    final String executionId = "exec-build-cmd";
    final ExecutionControl control = mock(ExecutionControl.class);
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then - verify the message has correct properties
    StepVerifier.create(result).verifyComplete();

    final ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    final Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.isControlMessage()).isTrue();
    assertThat(emittedMessage.getSourceNodeId()).isEqualTo("CONTROL_BUS");
  }

  @Test
  void pauseWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final ExecutionControl control = mock(ExecutionControl.class);
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then - error should be propagated
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void resumeWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final ExecutionControl control = mock(ExecutionControl.class);
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.of(control));
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.resumeWorkflow(executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void pauseNode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    stubNodeExists(executionId, nodeId);
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.pauseNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void resumeNode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    stubNodeExists(executionId, nodeId);
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.resumeNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stopNode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    stubNodeExists(executionId, nodeId);
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.stopNode(executionId, nodeId, true, "test reason");

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stopNode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-stop-node-not-found";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.stopNode(executionId, nodeId, false, "reason");

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void stopNode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-stop-execution-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.stopNode(executionId, nodeId, false, "reason");

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void skipNode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    stubNodeExists(executionId, nodeId);
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.skipNode(executionId, nodeId, true);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void skipNode_nodeNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-skip-node-not-found";
    final String nodeId = "missing-node";
    stubNoNodes(executionId);

    // When
    final Mono<Void> result = gateway.skipNode(executionId, nodeId, true);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Node not found")
                    && err.getMessage().contains(nodeId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void skipNode_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-skip-execution-not-found";
    final String nodeId = "node-x";
    when(executionControlRegistry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final Mono<Void> result = gateway.skipNode(executionId, nodeId, true);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Execution not found")
                    && err.getMessage().contains(executionId))
        .verify();
    verify(controlBusService, never()).emit(any());
  }

  @Test
  void enableStepMode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.enableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void disableStepMode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.disableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stepNode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String nodeId = "node-error";
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.stepNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void restartWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<String> result = gateway.restartWorkflow(executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void restartFromNode_emitError_logsErrorAndPropagates() {
    // Given
    final String executionId = "exec-error";
    final String fromNodeId = "node-error";
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    final Mono<String> result = gateway.restartFromNode(executionId, fromNodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void emit_validMessage_logsAndEmits() {
    // Given
    final Message<String> signal = DefaultMessage.create(null, "test-payload");
    when(controlBusService.emit(signal)).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.emit(signal);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).emit(signal);
  }

  @Test
  void watchExecution_validExecutionId_logsAndReturnsStream() {
    // Given
    final String executionId = "exec-watch";
    final WorkflowProgress progress =
        new WorkflowProgress("exec", "sess", "wf", "status", List.of(), null, null);
    when(taskTracker.getStatusStream(executionId, true)).thenReturn(Flux.just(progress));

    // When
    final Flux<WorkflowProgress> result = gateway.watchExecution(executionId, true);

    // Then
    StepVerifier.create(result).expectNext(progress).verifyComplete();
    verify(taskTracker).getStatusStream(executionId, true);
  }

  @Test
  void watchLogs_validExecutionId_logsAndReturnsStream() {
    // Given
    final String sessionId = "session-2";
    final String executionId = "exec-logs";
    final String logLine1 = "log line 1";
    final String logLine2 = "log line 2";
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.just(logLine1, logLine2));

    // When
    final Flux<String> result = gateway.watchLogs(sessionId, executionId);

    // Then
    StepVerifier.create(result).expectNext(logLine1, logLine2).verifyComplete();
  }

  @Test
  void getCurrentProgress_progressExists_logsAndReturnsProgress() {
    // Given
    final String executionId = "exec-progress";
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.status()).thenReturn("RUNNING");
    when(progress.tasks()).thenReturn(List.of());
    when(taskTracker.getProgressByExecutionId(executionId)).thenReturn(progress);

    // When
    final WorkflowProgress result = gateway.getCurrentProgress(executionId);

    // Then
    assertThat(result).isEqualTo(progress);
  }

  @Test
  void getCurrentProgress_progressNotFound_logsWarning() {
    // Given
    final String executionId = "exec-notfound";
    when(taskTracker.getProgressByExecutionId(executionId)).thenReturn(null);

    // When
    final WorkflowProgress result = gateway.getCurrentProgress(executionId);

    // Then
    assertThat(result).isNull();
  }

  @Test
  void getHistory_validSessionId_logsAndReturnsHistory() {
    // Given
    final String sessionId = "session-history";
    final WorkflowExecutionSummary summary1 = mock(WorkflowExecutionSummary.class);
    final WorkflowExecutionSummary summary2 = mock(WorkflowExecutionSummary.class);
    final List<WorkflowExecutionSummary> history = List.of(summary1, summary2);
    when(taskTracker.getHistory(sessionId)).thenReturn(history);

    // When
    final List<WorkflowExecutionSummary> result = gateway.getHistory(sessionId);

    // Then
    assertThat(result).isEqualTo(history);
    assertThat(result).hasSize(2);
  }

  @Test
  void getLastHeartbeat_heartbeatNotFound_logsDebug() {
    // Given
    final String workflowId = "wf-no-hb";
    final String nodeId = "node-no-hb";
    when(controlBusService.getLastHeartbeat(workflowId, nodeId)).thenReturn(null);

    // When
    final Message<?> result = gateway.getLastHeartbeat(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
  }

  @Test
  void getLastStatistics_statisticsNotFound_logsDebug() {
    // Given
    final String workflowId = "wf-no-stats";
    final String nodeId = "node-no-stats";
    when(controlBusService.getLastStatistics(workflowId, nodeId)).thenReturn(null);

    // When
    final Message<?> result = gateway.getLastStatistics(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
  }

  @Test
  void getActiveNodes_withWorkflowId_logsAndReturnsNodes() {
    // Given
    final String workflowId = "wf-active";
    final List<String> activeNodes = List.of("node-1", "node-2", "node-3");
    when(controlBusService.getActiveNodes(workflowId)).thenReturn(activeNodes);

    // When
    final List<String> result = gateway.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEqualTo(activeNodes);
    assertThat(result).hasSize(3);
  }

  @Test
  void getActiveNodes_noWorkflowId_logsAndReturnsAllNodes() {
    // Given
    final List<String> allActiveNodes = List.of("node-a", "node-b", "node-c", "node-d");
    when(controlBusService.getActiveNodes()).thenReturn(allActiveNodes);

    // When
    final List<String> result = gateway.getActiveNodes();

    // Then
    assertThat(result).isEqualTo(allActiveNodes);
    assertThat(result).hasSize(4);
  }

  @Test
  void compileAndCacheWorkflow_validInputs_logsAndCompiles() {
    // Given
    final String sessionId = "sess-compile";
    final WorkflowDefinition definition =
        new WorkflowDefinition("wf-compile", "desc", List.of(), List.of());
    when(controlBusService.compileAndCacheWorkflow(sessionId, definition)).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.compileAndCacheWorkflow(sessionId, definition);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void executeCommand_validCommand_logsAndExecutes() {
    // Given
    final PauseWorkflowCommand command = new PauseWorkflowCommand("exec-cmd");
    final Message<PauseWorkflowCommand> cmdMessage = DefaultMessage.create(null, command);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    final Mono<Void> result = gateway.executeCommand(cmdMessage);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void emit_emitError_logsErrorAndPropagates() {
    // Given
    final Message<String> signal = DefaultMessage.create(null, "test");
    final RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(signal)).thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.emit(signal);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void sendCommand_sendError_logsErrorAndPropagates() {
    // Given
    final String workflowId = "wf-send-error";
    final String nodeId = "node-send-error";
    final Message<?> command = DefaultMessage.create(null, "cmd");
    final RuntimeException testError = new RuntimeException("Send failed");
    when(controlBusService.sendCommand(anyString(), anyString(), any()))
        .thenReturn(Mono.error(testError));

    // When
    final Mono<Message<?>> result = gateway.sendCommand(workflowId, nodeId, command);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void compileAndCacheWorkflow_compileError_logsErrorAndPropagates() {
    // Given
    final String sessionId = "sess-compile-error";
    final WorkflowDefinition definition =
        new WorkflowDefinition("wf-error", "desc", List.of(), List.of());
    final RuntimeException testError = new RuntimeException("Compile failed");
    when(controlBusService.compileAndCacheWorkflow(sessionId, definition))
        .thenReturn(Mono.error(testError));

    // When
    final Mono<Void> result = gateway.compileAndCacheWorkflow(sessionId, definition);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void watchExecution_withEmptyStream_completesWithoutItems() {
    // Given
    final String executionId = "exec-empty";
    when(taskTracker.getStatusStream(executionId, true)).thenReturn(Flux.empty());

    // When
    final Flux<WorkflowProgress> result = gateway.watchExecution(executionId, true);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(taskTracker).getStatusStream(executionId, true);
  }

  @Test
  void watchExecution_withStreamError_propagatesError() {
    // Given
    final String executionId = "exec-stream-error";
    final RuntimeException testError = new RuntimeException("Stream error");
    when(taskTracker.getStatusStream(executionId, true)).thenReturn(Flux.error(testError));

    // When
    final Flux<WorkflowProgress> result = gateway.watchExecution(executionId, true);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
    verify(taskTracker).getStatusStream(executionId, true);
  }

  @Test
  void watchLogs_withEmptyStream_completesWithoutItems() {
    // Given
    final String sessionId = "session-3";
    final String executionId = "exec-empty-logs";
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.empty());

    // When
    final Flux<String> result = gateway.watchLogs(sessionId, executionId);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void watchLogs_withStreamError_propagatesError() {
    // Given
    final String sessionId = "session-4";
    final String executionId = "exec-logs-error";
    final RuntimeException testError = new RuntimeException("Logs stream error");
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.error(testError));

    // When
    final Flux<String> result = gateway.watchLogs(sessionId, executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void registerPlugin_multipleCalls_registersEachPlugin() {
    // Given
    final String workflowId = "wf-multi";
    final String nodeId1 = "node-1";
    final String nodeId2 = "node-2";
    final Plugin plugin1 = mock(Plugin.class);
    final Plugin plugin2 = mock(Plugin.class);

    // When
    gateway.registerPlugin(workflowId, nodeId1, plugin1);
    gateway.registerPlugin(workflowId, nodeId2, plugin2);

    // Then
    verify(controlBusService).registerPlugin(workflowId, nodeId1, plugin1);
    verify(controlBusService).registerPlugin(workflowId, nodeId2, plugin2);
  }

  @Test
  void getHistory_emptyHistory_returnsEmptyList() {
    // Given
    final String sessionId = "sess-empty-history";
    when(taskTracker.getHistory(sessionId)).thenReturn(List.of());

    // When
    final List<WorkflowExecutionSummary> result = gateway.getHistory(sessionId);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void getActiveNodes_emptyList_returnsEmpty() {
    // Given
    final String workflowId = "wf-no-active";
    when(controlBusService.getActiveNodes(workflowId)).thenReturn(List.of());

    // When
    final List<String> result = gateway.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void getActiveNodes_allNodesEmpty_returnsEmpty() {
    // Given
    when(controlBusService.getActiveNodes()).thenReturn(List.of());

    // When
    final List<String> result = gateway.getActiveNodes();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getLastHeartbeat_heartbeatFound_returnsHeartbeat() {
    // Given
    final String workflowId = "wf-hb-found";
    final String nodeId = "node-hb-found";
    final Message<String> heartbeat = DefaultMessage.create(null, "heartbeat-payload");
    when((Message<String>) controlBusService.getLastHeartbeat(workflowId, nodeId))
        .thenReturn(heartbeat);

    // When
    final Message<?> result = gateway.getLastHeartbeat(workflowId, nodeId);

    // Then - covers the non-null branch (line 591-594)
    assertThat(result).isEqualTo(heartbeat);
    verify(controlBusService).getLastHeartbeat(workflowId, nodeId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void getLastStatistics_statisticsFound_returnsStatistics() {
    // Given
    final String workflowId = "wf-stats-found";
    final String nodeId = "node-stats-found";
    final Message<String> statistics = DefaultMessage.create(null, "stats-payload");
    when((Message<String>) controlBusService.getLastStatistics(workflowId, nodeId))
        .thenReturn(statistics);

    // When
    final Message<?> result = gateway.getLastStatistics(workflowId, nodeId);

    // Then - covers the non-null branch (line 612-615)
    assertThat(result).isEqualTo(statistics);
    verify(controlBusService).getLastStatistics(workflowId, nodeId);
  }

  // --- startWorkflow Tests ---

  @Test
  void startWorkflow_workflowFound_returnsNewExecutionId() {
    // Given
    final String sessionId = "sess-start";
    final String workflowId = "wf-start";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "test workflow", List.of(), List.of());
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(
            executionId -> {
              assertThat(executionId).isNotNull();
              // Verify it's a valid UUID format
              try {
                UUID.fromString(executionId);
              } catch (final IllegalArgumentException e) {
                throw new AssertionError("Not a valid UUID: " + executionId, e);
              }
            })
        .verifyComplete();

    verify(workflowDefinitionStore).find(sessionId, workflowId);
    verify(preparedWorkflowCache).put(eq(sessionId), eq(workflowId), any());
    verify(orchestrator).prepareWorkflow(definition);
    verify(orchestrator)
        .execute(eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap());
  }

  @Test
  void startWorkflow_workflowNotFound_throwsIllegalArgumentException() {
    // Given
    final String sessionId = "sess-not-found";
    final String workflowId = "wf-not-found";
    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            err ->
                err instanceof IllegalArgumentException
                    && err.getMessage().contains("Workflow not found for session")
                    && err.getMessage().contains(sessionId)
                    && err.getMessage().contains(workflowId))
        .verify();

    verify(workflowDefinitionStore).find(sessionId, workflowId);
  }

  @Test
  void startWorkflow_preparedWorkflowCached_usesCachedVersion() {
    // Given
    final String sessionId = "sess-cached";
    final String workflowId = "wf-cached";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "cached workflow", List.of(), List.of());
    final PreparedWorkflow cachedPreparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId))
        .thenReturn(Optional.of(cachedPreparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(cachedPreparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(executionId -> assertThat(executionId).isNotNull())
        .verifyComplete();

    // Verify prepareWorkflow was NOT called since we used cache
    verify(orchestrator, times(0)).prepareWorkflow(any());
    // Verify execute was still called with cached workflow
    verify(orchestrator)
        .execute(eq(sessionId), eq(workflowId), anyString(), eq(cachedPreparedWorkflow), anyMap());
  }

  @Test
  void startWorkflow_preparedWorkflowNotCached_preparesAndCaches() {
    // Given
    final String sessionId = "sess-prepare";
    final String workflowId = "wf-prepare";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow to prepare", List.of(), List.of());
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(executionId -> assertThat(executionId).isNotNull())
        .verifyComplete();

    // Verify prepareWorkflow was called
    verify(orchestrator).prepareWorkflow(definition);
    // Verify cache.put was called with the prepared workflow
    final ArgumentCaptor<PreparedWorkflow> cacheCaptor =
        ArgumentCaptor.forClass(PreparedWorkflow.class);
    verify(preparedWorkflowCache).put(eq(sessionId), eq(workflowId), cacheCaptor.capture());
    assertThat(cacheCaptor.getValue()).isEqualTo(preparedWorkflow);
  }

  @Test
  void startWorkflow_orchestratorPrepareFailure_propagatesError() {
    // Given
    final String sessionId = "sess-prepare-fail";
    final String workflowId = "wf-prepare-fail";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    final RuntimeException testError = new RuntimeException("Prepare failed");

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.error(testError));

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void startWorkflow_orchestratorExecuteFailure_returnsExecutionIdAndRunsAsync() {
    // Given - orchestrator execute errors are now handled asynchronously
    final String sessionId = "sess-exec-fail";
    final String workflowId = "wf-exec-fail";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);
    final RuntimeException testError = new RuntimeException("Execute failed");

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.error(testError));

    // When - execution starts asynchronously, so we get execution ID immediately
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then - returns execution ID without waiting for execution to complete
    StepVerifier.create(result).expectNextMatches(id -> !id.isEmpty()).verifyComplete();
  }

  @Test
  void startWorkflow_workflowDefinitionStoreError_propagatesError() {
    // Given
    final String sessionId = "sess-store-error";
    final String workflowId = "wf-store-error";
    final RuntimeException testError = new RuntimeException("Store error");

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.error(testError));

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void startWorkflow_executionIdUnique_generatesNewIdEachTime() {
    // Given
    final String sessionId = "sess-unique";
    final String workflowId = "wf-unique";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When - call twice
    final Mono<String> result1 = gateway.startWorkflow(sessionId, workflowId);
    final Mono<String> result2 = gateway.startWorkflow(sessionId, workflowId);

    // Then - execution IDs should be different
    final String execId1 = result1.block();
    final String execId2 = result2.block();

    assertThat(execId1).isNotEqualTo(execId2);
    assertThat(execId1).isNotNull();
    assertThat(execId2).isNotNull();
  }

  @Test
  void startWorkflow_executePassesCorrectParameters() {
    // Given
    final String sessionId = "sess-params";
    final String workflowId = "wf-params";
    final WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId))
        .thenReturn(Optional.of(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    final Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(executionId -> assertThat(executionId).isNotNull())
        .verifyComplete();

    final ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<String> workflowCaptor = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<String> executionIdCaptor = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<PreparedWorkflow> preparedCaptor =
        ArgumentCaptor.forClass(PreparedWorkflow.class);
    final ArgumentCaptor<Map> contextMapCaptor = ArgumentCaptor.forClass(Map.class);

    verify(orchestrator)
        .execute(
            sessionCaptor.capture(),
            workflowCaptor.capture(),
            executionIdCaptor.capture(),
            preparedCaptor.capture(),
            contextMapCaptor.capture());

    assertThat(sessionCaptor.getValue()).isEqualTo(sessionId);
    assertThat(workflowCaptor.getValue()).isEqualTo(workflowId);
    assertThat(contextMapCaptor.getValue()).isEmpty();
  }

  @Test
  void startWorkflow_multipleWorkflows_cachesEachSeparately() {
    // Given
    final String sessionId = "sess-multi";
    final String workflowId1 = "wf-1";
    final String workflowId2 = "wf-2";
    final WorkflowDefinition definition1 =
        new WorkflowDefinition(workflowId1, "workflow 1", List.of(), List.of());
    final WorkflowDefinition definition2 =
        new WorkflowDefinition(workflowId2, "workflow 2", List.of(), List.of());
    final PreparedWorkflow preparedWorkflow1 = mock(PreparedWorkflow.class);
    final PreparedWorkflow preparedWorkflow2 = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId1)).thenReturn(Mono.just(definition1));
    when(workflowDefinitionStore.find(sessionId, workflowId2)).thenReturn(Mono.just(definition2));
    when(preparedWorkflowCache.get(sessionId, workflowId1)).thenReturn(Optional.empty());
    when(preparedWorkflowCache.get(sessionId, workflowId2)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition1)).thenReturn(Mono.just(preparedWorkflow1));
    when(orchestrator.prepareWorkflow(definition2)).thenReturn(Mono.just(preparedWorkflow2));
    when(orchestrator.execute(eq(sessionId), anyString(), anyString(), any(), anyMap()))
        .thenReturn(Mono.empty());

    // When
    final Mono<String> result1 = gateway.startWorkflow(sessionId, workflowId1);
    final Mono<String> result2 = gateway.startWorkflow(sessionId, workflowId2);

    // Then
    final String execId1 = result1.block();
    final String execId2 = result2.block();

    assertThat(execId1).isNotNull();
    assertThat(execId2).isNotNull();
    assertThat(execId1).isNotEqualTo(execId2);

    // Verify both workflows were cached separately
    verify(preparedWorkflowCache).put(sessionId, workflowId1, preparedWorkflow1);
    verify(preparedWorkflowCache).put(sessionId, workflowId2, preparedWorkflow2);
  }
}
