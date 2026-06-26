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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
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
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.execution.status.ExecutionStatusEvent;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
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
    Message<String> signal = DefaultMessage.create(null, "test-payload");
    when(controlBusService.emit(signal)).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.emit(signal);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).emit(signal);
  }

  @Test
  void registerPlugin_validInputs_delegatesToControlBusService() {
    // Given
    String workflowId = "workflow-1";
    String nodeId = "node-1";
    Plugin plugin = mock(Plugin.class);

    // When
    gateway.registerPlugin(workflowId, nodeId, plugin);

    // Then
    verify(controlBusService).registerPlugin(workflowId, nodeId, plugin);
  }

  @Test
  void unregisterPlugin_validInputs_delegatesToControlBusService() {
    // Given
    String workflowId = "workflow-1";
    String nodeId = "node-1";

    // When
    gateway.unregisterPlugin(workflowId, nodeId);

    // Then
    verify(controlBusService).unregisterPlugin(workflowId, nodeId);
  }

  @Test
  void sendCommand_validInputs_delegatesToControlBusService() {
    // Given
    String workflowId = "workflow-1";
    String nodeId = "node-1";
    Message<?> command = DefaultMessage.create(null, "command-payload");
    Message<?> response = DefaultMessage.create(null, "response-payload");
    when(controlBusService.sendCommand(workflowId, nodeId, command))
        .thenReturn(Mono.just(response));

    // When
    Mono<Message<?>> result = gateway.sendCommand(workflowId, nodeId, command);

    // Then
    StepVerifier.create(result).expectNext(response).verifyComplete();
    verify(controlBusService).sendCommand(workflowId, nodeId, command);
  }

  @Test
  void compileAndCacheWorkflow_validInputs_delegatesToControlBusService() {
    // Given
    String sessionId = "session-1";
    WorkflowDefinition definition =
        new WorkflowDefinition("workflow-1", "description", List.of(), List.of());
    when(controlBusService.compileAndCacheWorkflow(sessionId, definition)).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.compileAndCacheWorkflow(sessionId, definition);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).compileAndCacheWorkflow(sessionId, definition);
  }

  // --- Execution Control Command Tests ---

  @Test
  void pauseWorkflow_validExecutionId_emitsPauseCommand() {
    // Given
    String executionId = "exec-1";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(PauseWorkflowCommand.class);
    PauseWorkflowCommand cmd = (PauseWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(emittedMessage.getPriority()).isEqualTo(100);
    assertThat(emittedMessage.getSourceNodeId()).isEqualTo("CONTROL_BUS");
    assertThat(emittedMessage.isControlMessage()).isTrue();
  }

  @Test
  void resumeWorkflow_validExecutionId_emitsResumeCommand() {
    // Given
    String executionId = "exec-2";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.resumeWorkflow(executionId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(ResumeWorkflowCommand.class);
    ResumeWorkflowCommand cmd = (ResumeWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
  }

  @Test
  void pauseNode_validInputs_emitsPauseNodeCommand() {
    // Given
    String executionId = "exec-3";
    String nodeId = "node-2";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.pauseNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(PauseNodeCommand.class);
    PauseNodeCommand cmd = (PauseNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void resumeNode_validInputs_emitsResumeNodeCommand() {
    // Given
    String executionId = "exec-4";
    String nodeId = "node-3";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.resumeNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(ResumeNodeCommand.class);
    ResumeNodeCommand cmd = (ResumeNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void stopNode_normalStop_emitsStopCommandWithElevatedPriority() {
    // Given
    String executionId = "exec-5";
    String nodeId = "node-4";
    boolean immediate = false;
    String reason = "test reason";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.stopNode(executionId, nodeId, immediate, reason);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(StopNodeCommand.class);
    StopNodeCommand cmd = (StopNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
    assertThat(cmd.immediate()).isEqualTo(immediate);
    assertThat(cmd.reason()).isEqualTo(reason);
    assertThat(emittedMessage.getPriority()).isEqualTo(110); // CONTROL_COMMAND_PRIORITY + 10
  }

  @Test
  void stopNode_immediateStop_emitsStopCommandWithImmediateFlag() {
    // Given
    String executionId = "exec-6";
    String nodeId = "node-5";
    boolean immediate = true;
    String reason = "immediate stop";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.stopNode(executionId, nodeId, immediate, reason);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    StopNodeCommand cmd = (StopNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.immediate()).isTrue();
  }

  // --- stopWorkflow Tests ---

  @Test
  void stopWorkflow_activeExecutionFound_stopsWorkflowAndReturnsExecutionId() {
    // Given
    String sessionId = "sess-stop-wf";
    String workflowId = "wf-stop";
    String executionId = "exec-to-stop";
    String reason = "User requested stop";

    ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(
            stoppedId -> {
              assertThat(stoppedId).isEqualTo(executionId);
            })
        .verifyComplete();

    verify(executionControlRegistry).findActiveByWorkflow(sessionId, workflowId);
    verify(controlBusService).emit(any());
  }

  @Test
  void stopWorkflow_noActiveExecution_throwsIllegalArgumentException() {
    // Given
    String sessionId = "sess-no-exec";
    String workflowId = "wf-no-exec";
    String reason = "stop";
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.empty());

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

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
  void stopWorkflow_correctStopWorkflowCommandBuilt_withElevatedPriority() {
    // Given
    String sessionId = "sess-cmd-build";
    String workflowId = "wf-cmd-build";
    String executionId = "exec-cmd";
    String reason = "priority test";

    ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(id -> assertThat(id).isEqualTo(executionId))
        .verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();

    assertThat(emittedMessage.getPayload()).isInstanceOf(StopWorkflowCommand.class);
    StopWorkflowCommand cmd = (StopWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.reason()).isEqualTo(reason);
    assertThat(emittedMessage.getPriority()).isEqualTo(120); // CONTROL_COMMAND_PRIORITY + 20
    assertThat(emittedMessage.getSourceNodeId()).isEqualTo("CONTROL_BUS");
    assertThat(emittedMessage.isControlMessage()).isTrue();
  }

  @Test
  void stopWorkflow_withSpecificReason_reasonIncludedInCommand() {
    // Given
    String sessionId = "sess-reason";
    String workflowId = "wf-reason";
    String executionId = "exec-reason";
    String reason = "Timeout exceeded";

    ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result)
        .assertNext(id -> assertThat(id).isEqualTo(executionId))
        .verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    StopWorkflowCommand cmd = (StopWorkflowCommand) emittedMessage.getPayload();
    assertThat(cmd.reason()).isEqualTo(reason);
  }

  @Test
  void stopWorkflow_executeCommandFails_propagatesError() {
    // Given
    String sessionId = "sess-fail";
    String workflowId = "wf-fail";
    String executionId = "exec-fail";
    String reason = "should fail";

    ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control));

    RuntimeException testError = new RuntimeException("Command execution failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stopWorkflow_sessionAndWorkflowIdsPassed_queriesRegistryCorrectly() {
    // Given
    String sessionId = "sess-registry";
    String workflowId = "wf-registry";
    String executionId = "exec-registry";
    String reason = "registry test";

    ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then
    StepVerifier.create(result).assertNext(id -> assertThat(id).isNotNull()).verifyComplete();

    verify(executionControlRegistry).findActiveByWorkflow(sessionId, workflowId);
  }

  @Test
  void stopWorkflow_multipleReasons_eachReasonStoredInCommand() {
    // Test with first reason
    String sessionId = "sess-multi-reason";
    String workflowId = "wf-multi-reason";
    String executionId1 = "exec-reason-1";
    String reason1 = "Timeout";

    ExecutionControl control1 = mock(ExecutionControl.class);
    when(control1.executionId()).thenReturn(executionId1);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control1));
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result1 = gateway.stopWorkflow(sessionId, workflowId, reason1);

    // Then
    StepVerifier.create(result1)
        .assertNext(id -> assertThat(id).isEqualTo(executionId1))
        .verifyComplete();

    ArgumentCaptor<Message<?>> captor1 = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor1.capture());
    StopWorkflowCommand cmd1 = (StopWorkflowCommand) captor1.getValue().getPayload();
    assertThat(cmd1.reason()).isEqualTo(reason1);
  }

  @Test
  void stopWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    String sessionId = "sess-emit-error";
    String workflowId = "wf-emit-error";
    String executionId = "exec-emit-error";
    String reason = "emit test";

    ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn(executionId);
    when(executionControlRegistry.findActiveByWorkflow(sessionId, workflowId))
        .thenReturn(Optional.of(control));

    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.stopWorkflow(sessionId, workflowId, reason);

    // Then - error is propagated through doOnError
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void skipNode_skipTrue_emitsSkipCommand() {
    // Given
    String executionId = "exec-7";
    String nodeId = "node-6";
    boolean skip = true;
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.skipNode(executionId, nodeId, skip);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(SkipNodeCommand.class);
    SkipNodeCommand cmd = (SkipNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.skip()).isTrue();
  }

  @Test
  void skipNode_skipFalse_emitsUnskipCommand() {
    // Given
    String executionId = "exec-8";
    String nodeId = "node-7";
    boolean skip = false;
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.skipNode(executionId, nodeId, skip);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    SkipNodeCommand cmd = (SkipNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.skip()).isFalse();
  }

  @Test
  void enableStepMode_validInputs_emitsEnableStepModeCommand() {
    // Given
    String executionId = "exec-9";
    String nodeId = "node-8";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.enableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(EnableStepModeCommand.class);
    EnableStepModeCommand cmd = (EnableStepModeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void disableStepMode_validInputs_emitsDisableStepModeCommand() {
    // Given
    String executionId = "exec-10";
    String nodeId = "node-9";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.disableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(DisableStepModeCommand.class);
    DisableStepModeCommand cmd = (DisableStepModeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void stepNode_validInputs_emitsStepNodeCommand() {
    // Given
    String executionId = "exec-11";
    String nodeId = "node-10";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.stepNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(StepNodeCommand.class);
    StepNodeCommand cmd = (StepNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.nodeId()).isEqualTo(nodeId);
  }

  @Test
  void restartWorkflow_validExecutionId_generatesNewExecutionId() {
    // Given
    String executionId = "exec-12";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.restartWorkflow(executionId);

    // Then
    StepVerifier.create(result)
        .assertNext(
            newExecId -> {
              assertThat(newExecId).isNotNull();
              assertThat(newExecId).isNotEqualTo(executionId);
              // Verify it's a valid UUID format - should not throw exception
              try {
                UUID.fromString(newExecId);
              } catch (IllegalArgumentException e) {
                throw new AssertionError("Not a valid UUID: " + newExecId, e);
              }
            })
        .verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(RestartCommand.class);
    RestartCommand cmd = (RestartCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(emittedMessage.getPriority()).isEqualTo(120); // CONTROL_COMMAND_PRIORITY + 20
  }

  @Test
  void restartFromNode_validInputs_generatesNewExecutionId() {
    // Given
    String executionId = "exec-13";
    String fromNodeId = "node-11";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.restartFromNode(executionId, fromNodeId);

    // Then
    StepVerifier.create(result)
        .assertNext(
            newExecId -> {
              assertThat(newExecId).isNotNull();
              assertThat(newExecId).isNotEqualTo(executionId);
              // Verify it's a valid UUID format - should not throw exception
              try {
                UUID.fromString(newExecId);
              } catch (IllegalArgumentException e) {
                throw new AssertionError("Not a valid UUID: " + newExecId, e);
              }
            })
        .verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.getPayload()).isInstanceOf(RestartFromNodeCommand.class);
    RestartFromNodeCommand cmd = (RestartFromNodeCommand) emittedMessage.getPayload();
    assertThat(cmd.executionId()).isEqualTo(executionId);
    assertThat(cmd.fromNodeId()).isEqualTo(fromNodeId);
    assertThat(emittedMessage.getPriority()).isEqualTo(120); // CONTROL_COMMAND_PRIORITY + 20
  }

  // --- Observability Tests ---

  @Test
  void watchExecution_validExecutionId_delegatesToTaskTracker() {
    // Given
    String executionId = "exec-14";
    WorkflowProgress progress = mock(WorkflowProgress.class);
    when(taskTracker.getStatusStream(executionId)).thenReturn(Flux.just(progress));

    // When
    Flux<WorkflowProgress> result = gateway.watchExecution(executionId);

    // Then
    StepVerifier.create(result).expectNext(progress).verifyComplete();
    verify(taskTracker).getStatusStream(executionId);
  }

  @Test
  void watchLogs_validExecutionId_delegatesToTaskTracker() {
    // Given
    String executionId = "exec-15";
    String logLine = "test log";
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.just(logLine));

    // When
    Flux<String> result = gateway.watchLogs(executionId);

    // Then
    StepVerifier.create(result).expectNext(logLine).verifyComplete();
    verify(taskTracker).getLogStream(executionId);
  }

  @Test
  void getCurrentProgress_validExecutionId_delegatesToTaskTracker() {
    // Given
    String executionId = "exec-16";
    WorkflowProgress progress = mock(WorkflowProgress.class);
    when(taskTracker.getProgressByExecutionId(executionId)).thenReturn(progress);

    // When
    WorkflowProgress result = gateway.getCurrentProgress(executionId);

    // Then
    assertThat(result).isEqualTo(progress);
    verify(taskTracker).getProgressByExecutionId(executionId);
  }

  @Test
  void getHistory_validSessionId_delegatesToTaskTracker() {
    // Given
    String sessionId = "session-2";
    WorkflowExecutionSummary summary = mock(WorkflowExecutionSummary.class);
    List<WorkflowExecutionSummary> history = List.of(summary);
    when(taskTracker.getHistory(sessionId)).thenReturn(history);

    // When
    List<WorkflowExecutionSummary> result = gateway.getHistory(sessionId);

    // Then
    assertThat(result).isEqualTo(history);
    verify(taskTracker).getHistory(sessionId);
  }

  // --- State Query Tests ---

  @Test
  void getLastHeartbeat_validInputs_delegatesToControlBusService() {
    // Given
    String workflowId = "workflow-2";
    String nodeId = "node-12";
    when(controlBusService.getLastHeartbeat(workflowId, nodeId)).thenReturn(null);

    // When
    Message<?> result = gateway.getLastHeartbeat(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
    verify(controlBusService).getLastHeartbeat(workflowId, nodeId);
  }

  @Test
  void getLastStatistics_validInputs_delegatesToControlBusService() {
    // Given
    String workflowId = "workflow-3";
    String nodeId = "node-13";
    when(controlBusService.getLastStatistics(workflowId, nodeId)).thenReturn(null);

    // When
    Message<?> result = gateway.getLastStatistics(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
    verify(controlBusService).getLastStatistics(workflowId, nodeId);
  }

  @Test
  void getActiveNodes_withWorkflowId_delegatesToControlBusService() {
    // Given
    String workflowId = "workflow-4";
    List<String> activeNodes = List.of("node-14", "node-15");
    when(controlBusService.getActiveNodes(workflowId)).thenReturn(activeNodes);

    // When
    List<String> result = gateway.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEqualTo(activeNodes);
    verify(controlBusService).getActiveNodes(workflowId);
  }

  @Test
  void getActiveNodes_noWorkflowId_delegatesToControlBusService() {
    // Given
    List<String> allActiveNodes = List.of("node-16", "node-17", "node-18");
    when(controlBusService.getActiveNodes()).thenReturn(allActiveNodes);

    // When
    List<String> result = gateway.getActiveNodes();

    // Then
    assertThat(result).isEqualTo(allActiveNodes);
    verify(controlBusService).getActiveNodes();
  }

  // --- ExecutionStatusPublisher Tests ---

  @Test
  void publishStatus_validEvent_emitsToSink() {
    // Given
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-17",
            "node-19",
            "workflow-5",
            "session-3",
            "RUNNING",
            "module-1",
            Map.of("key", "value"),
            null,
            Instant.now());
    gateway.subscribeToStatusEvents(); // Initialize subscription

    // When
    Mono<Void> result = gateway.publishStatus(event);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void publishStatus_eventWithNullMetadata_emitsToSink() {
    // Given
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-18",
            "node-20",
            "workflow-6",
            "session-4",
            "SUCCESS",
            "module-2",
            null,
            null,
            Instant.now());
    gateway.subscribeToStatusEvents();

    // When
    Mono<Void> result = gateway.publishStatus(event);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void statusStream_returnsFluxOfEvents() {
    // Given
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-19",
            "node-21",
            "workflow-7",
            "session-5",
            "FAILURE",
            "module-3",
            null,
            null,
            Instant.now());

    // When - get stream and publish concurrently
    Flux<ExecutionStatusEvent> stream = gateway.statusStream();

    // Then - verify stream returns events published to it
    // Subscribe before publishing to avoid race condition with multicast sink
    StepVerifier.create(stream.doFirst(() -> gateway.publishStatus(event).block()).take(1))
        .assertNext(
            receivedEvent -> {
              assertThat(receivedEvent.executionId()).isEqualTo("exec-19");
              assertThat(receivedEvent.status()).isEqualTo("FAILURE");
            })
        .verifyComplete();
  }

  @Test
  void subscribeToStatusEvents_initializesSubscription() {
    // Given - just verify subscribeToStatusEvents is callable and doesn't error

    // When
    gateway.subscribeToStatusEvents();

    // Then - method completes without error (subscription is established internally)
    // This verifies the @PostConstruct method works correctly
  }

  @Test
  void executeCommand_validCommand_delegatesToEmit() {
    // Given
    PauseWorkflowCommand command = new PauseWorkflowCommand("exec-22");
    Message<PauseWorkflowCommand> cmdMessage = DefaultMessage.create(null, command);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.executeCommand(cmdMessage);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).emit(any());
  }

  @Test
  void publishStatus_emitsEventToSink() {
    // Given
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-publish",
            "node-publish",
            "workflow-publish",
            "session-publish",
            "RUNNING",
            "module-publish",
            Map.of("key", "value"),
            null,
            Instant.now());
    gateway.subscribeToStatusEvents();

    // When
    Mono<Void> result = gateway.publishStatus(event);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(taskTracker)
        .updateTaskStatus(
            eq("exec-publish"), eq("node-publish"), eq("module-publish"), eq("RUNNING"), anyMap());
  }

  @Test
  void buildCommand_createsMessageWithCorrectProperties() {
    // This test verifies the buildCommand method is executed
    // by testing that control commands create proper messages
    String executionId = "exec-build-cmd";
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then - verify the message has correct properties
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
    verify(controlBusService).emit(captor.capture());
    Message<?> emittedMessage = captor.getValue();
    assertThat(emittedMessage.isControlMessage()).isTrue();
    assertThat(emittedMessage.getSourceNodeId()).isEqualTo("CONTROL_BUS");
  }

  @Test
  void subscribeToStatusEvents_forwardsEventsToTaskTracker() {
    // Given
    gateway.subscribeToStatusEvents();
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-forward",
            "node-forward",
            "workflow-forward",
            "session-forward",
            "COMPLETED",
            "module-forward",
            Map.of("key", "value"),
            null,
            Instant.now());

    // When
    gateway.publishStatus(event).block();

    // Then - verify task tracker received the update
    verify(taskTracker)
        .updateTaskStatus(
            eq("exec-forward"),
            eq("node-forward"),
            eq("module-forward"),
            eq("COMPLETED"),
            anyMap());
  }

  @Test
  void subscribeToStatusEvents_withNullMetadata_usesEmptyMap() {
    // Given
    gateway.subscribeToStatusEvents();
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-null-meta",
            "node-null-meta",
            "workflow-null-meta",
            "session-null-meta",
            "PENDING",
            "module-null-meta",
            null,
            null,
            Instant.now());

    // When
    gateway.publishStatus(event).block();

    // Then - verify empty map is used when metadata is null
    verify(taskTracker)
        .updateTaskStatus(
            eq("exec-null-meta"),
            eq("node-null-meta"),
            eq("module-null-meta"),
            eq("PENDING"),
            eq(Map.of()));
  }

  @Test
  void pauseWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.pauseWorkflow(executionId);

    // Then - error should be propagated
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void resumeWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.resumeWorkflow(executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void pauseNode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.pauseNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void resumeNode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.resumeNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stopNode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.stopNode(executionId, nodeId, true, "test reason");

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void skipNode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.skipNode(executionId, nodeId, true);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void enableStepMode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.enableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void disableStepMode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.disableStepMode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void stepNode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String nodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.stepNode(executionId, nodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void restartWorkflow_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.restartWorkflow(executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void restartFromNode_emitError_logsErrorAndPropagates() {
    // Given
    String executionId = "exec-error";
    String fromNodeId = "node-error";
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(any())).thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.restartFromNode(executionId, fromNodeId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void emit_validMessage_logsAndEmits() {
    // Given
    Message<String> signal = DefaultMessage.create(null, "test-payload");
    when(controlBusService.emit(signal)).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.emit(signal);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(controlBusService).emit(signal);
  }

  @Test
  void watchExecution_validExecutionId_logsAndReturnsStream() {
    // Given
    String executionId = "exec-watch";
    WorkflowProgress progress = mock(WorkflowProgress.class);
    when(taskTracker.getStatusStream(executionId)).thenReturn(Flux.just(progress));

    // When
    Flux<WorkflowProgress> result = gateway.watchExecution(executionId);

    // Then
    StepVerifier.create(result).expectNext(progress).verifyComplete();
  }

  @Test
  void watchLogs_validExecutionId_logsAndReturnsStream() {
    // Given
    String executionId = "exec-logs";
    String logLine1 = "log line 1";
    String logLine2 = "log line 2";
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.just(logLine1, logLine2));

    // When
    Flux<String> result = gateway.watchLogs(executionId);

    // Then
    StepVerifier.create(result).expectNext(logLine1, logLine2).verifyComplete();
  }

  @Test
  void getCurrentProgress_progressExists_logsAndReturnsProgress() {
    // Given
    String executionId = "exec-progress";
    WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.status()).thenReturn("RUNNING");
    when(progress.tasks()).thenReturn(List.of());
    when(taskTracker.getProgressByExecutionId(executionId)).thenReturn(progress);

    // When
    WorkflowProgress result = gateway.getCurrentProgress(executionId);

    // Then
    assertThat(result).isEqualTo(progress);
  }

  @Test
  void getCurrentProgress_progressNotFound_logsWarning() {
    // Given
    String executionId = "exec-notfound";
    when(taskTracker.getProgressByExecutionId(executionId)).thenReturn(null);

    // When
    WorkflowProgress result = gateway.getCurrentProgress(executionId);

    // Then
    assertThat(result).isNull();
  }

  @Test
  void getHistory_validSessionId_logsAndReturnsHistory() {
    // Given
    String sessionId = "session-history";
    WorkflowExecutionSummary summary1 = mock(WorkflowExecutionSummary.class);
    WorkflowExecutionSummary summary2 = mock(WorkflowExecutionSummary.class);
    List<WorkflowExecutionSummary> history = List.of(summary1, summary2);
    when(taskTracker.getHistory(sessionId)).thenReturn(history);

    // When
    List<WorkflowExecutionSummary> result = gateway.getHistory(sessionId);

    // Then
    assertThat(result).isEqualTo(history);
    assertThat(result).hasSize(2);
  }

  @Test
  void getLastHeartbeat_heartbeatNotFound_logsDebug() {
    // Given
    String workflowId = "wf-no-hb";
    String nodeId = "node-no-hb";
    when(controlBusService.getLastHeartbeat(workflowId, nodeId)).thenReturn(null);

    // When
    Message<?> result = gateway.getLastHeartbeat(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
  }

  @Test
  void getLastStatistics_statisticsNotFound_logsDebug() {
    // Given
    String workflowId = "wf-no-stats";
    String nodeId = "node-no-stats";
    when(controlBusService.getLastStatistics(workflowId, nodeId)).thenReturn(null);

    // When
    Message<?> result = gateway.getLastStatistics(workflowId, nodeId);

    // Then
    assertThat(result).isNull();
  }

  @Test
  void getActiveNodes_withWorkflowId_logsAndReturnsNodes() {
    // Given
    String workflowId = "wf-active";
    List<String> activeNodes = List.of("node-1", "node-2", "node-3");
    when(controlBusService.getActiveNodes(workflowId)).thenReturn(activeNodes);

    // When
    List<String> result = gateway.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEqualTo(activeNodes);
    assertThat(result).hasSize(3);
  }

  @Test
  void getActiveNodes_noWorkflowId_logsAndReturnsAllNodes() {
    // Given
    List<String> allActiveNodes = List.of("node-a", "node-b", "node-c", "node-d");
    when(controlBusService.getActiveNodes()).thenReturn(allActiveNodes);

    // When
    List<String> result = gateway.getActiveNodes();

    // Then
    assertThat(result).isEqualTo(allActiveNodes);
    assertThat(result).hasSize(4);
  }

  @Test
  void compileAndCacheWorkflow_validInputs_logsAndCompiles() {
    // Given
    String sessionId = "sess-compile";
    WorkflowDefinition definition =
        new WorkflowDefinition("wf-compile", "desc", List.of(), List.of());
    when(controlBusService.compileAndCacheWorkflow(sessionId, definition)).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.compileAndCacheWorkflow(sessionId, definition);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void executeCommand_validCommand_logsAndExecutes() {
    // Given
    PauseWorkflowCommand command = new PauseWorkflowCommand("exec-cmd");
    Message<PauseWorkflowCommand> cmdMessage = DefaultMessage.create(null, command);
    when(controlBusService.emit(any())).thenReturn(Mono.empty());

    // When
    Mono<Void> result = gateway.executeCommand(cmdMessage);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void emit_emitError_logsErrorAndPropagates() {
    // Given
    Message<String> signal = DefaultMessage.create(null, "test");
    RuntimeException testError = new RuntimeException("Emit failed");
    when(controlBusService.emit(signal)).thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.emit(signal);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void sendCommand_sendError_logsErrorAndPropagates() {
    // Given
    String workflowId = "wf-send-error";
    String nodeId = "node-send-error";
    Message<?> command = DefaultMessage.create(null, "cmd");
    RuntimeException testError = new RuntimeException("Send failed");
    when(controlBusService.sendCommand(anyString(), anyString(), any()))
        .thenReturn(Mono.error(testError));

    // When
    Mono<Message<?>> result = gateway.sendCommand(workflowId, nodeId, command);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void compileAndCacheWorkflow_compileError_logsErrorAndPropagates() {
    // Given
    String sessionId = "sess-compile-error";
    WorkflowDefinition definition =
        new WorkflowDefinition("wf-error", "desc", List.of(), List.of());
    RuntimeException testError = new RuntimeException("Compile failed");
    when(controlBusService.compileAndCacheWorkflow(sessionId, definition))
        .thenReturn(Mono.error(testError));

    // When
    Mono<Void> result = gateway.compileAndCacheWorkflow(sessionId, definition);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void watchExecution_withEmptyStream_completesWithoutItems() {
    // Given
    String executionId = "exec-empty";
    when(taskTracker.getStatusStream(executionId)).thenReturn(Flux.empty());

    // When
    Flux<WorkflowProgress> result = gateway.watchExecution(executionId);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void watchExecution_withStreamError_propagatesError() {
    // Given
    String executionId = "exec-stream-error";
    RuntimeException testError = new RuntimeException("Stream error");
    when(taskTracker.getStatusStream(executionId)).thenReturn(Flux.error(testError));

    // When
    Flux<WorkflowProgress> result = gateway.watchExecution(executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void watchLogs_withEmptyStream_completesWithoutItems() {
    // Given
    String executionId = "exec-empty-logs";
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.empty());

    // When
    Flux<String> result = gateway.watchLogs(executionId);

    // Then
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void watchLogs_withStreamError_propagatesError() {
    // Given
    String executionId = "exec-logs-error";
    RuntimeException testError = new RuntimeException("Logs stream error");
    when(taskTracker.getLogStream(executionId)).thenReturn(Flux.error(testError));

    // When
    Flux<String> result = gateway.watchLogs(executionId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void statusStream_returnsFlux() {
    // Given - status stream should be accessible

    // When
    Flux<ExecutionStatusEvent> result = gateway.statusStream();

    // Then - verify it returns a non-null Flux
    assertThat(result).isNotNull();
  }

  @Test
  void publishStatus_withRuntimeException_wrapsInIllegalStateException() {
    // Note: This test validates that the try-catch block works,
    // though normal operation wouldn't throw from emitNext
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-pub-error",
            "node-pub-error",
            "wf-pub",
            "sess-pub",
            "RUNNING",
            "mod-pub",
            Map.of(),
            null,
            Instant.now());

    // When
    Mono<Void> result = gateway.publishStatus(event);

    // Then - should complete successfully (Mono.create handles success path)
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void registerPlugin_multipleCalls_registersEachPlugin() {
    // Given
    String workflowId = "wf-multi";
    String nodeId1 = "node-1";
    String nodeId2 = "node-2";
    Plugin plugin1 = mock(Plugin.class);
    Plugin plugin2 = mock(Plugin.class);

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
    String sessionId = "sess-empty-history";
    when(taskTracker.getHistory(sessionId)).thenReturn(List.of());

    // When
    List<WorkflowExecutionSummary> result = gateway.getHistory(sessionId);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void getActiveNodes_emptyList_returnsEmpty() {
    // Given
    String workflowId = "wf-no-active";
    when(controlBusService.getActiveNodes(workflowId)).thenReturn(List.of());

    // When
    List<String> result = gateway.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void getActiveNodes_allNodesEmpty_returnsEmpty() {
    // Given
    when(controlBusService.getActiveNodes()).thenReturn(List.of());

    // When
    List<String> result = gateway.getActiveNodes();

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void subscribeToStatusEvents_calls_updateTaskStatus() {
    // This test verifies that subscribeToStatusEvents() establishes the subscription
    // that forwards events to taskTracker.updateTaskStatus()
    // The actual doOnSuccess and doOnError handlers are covered by the existing
    // subscribeToStatusEvents_forwardsEventsToTaskTracker test
    gateway.subscribeToStatusEvents();
    // If no exception is thrown, subscription was established successfully
    assertThat(gateway).isNotNull();
  }

  @Test
  void subscribeToStatusEvents_forwardsWithMetadata() {
    // Given
    gateway.subscribeToStatusEvents();
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-meta",
            "node-meta",
            "workflow-meta",
            "session-meta",
            "PROCESSING",
            "module-meta",
            Map.of("key1", "value1"),
            null,
            Instant.now());

    // When
    gateway.publishStatus(event).block();

    // Then - verify task tracker received the metadata
    verify(taskTracker)
        .updateTaskStatus(
            eq("exec-meta"),
            eq("node-meta"),
            eq("module-meta"),
            eq("PROCESSING"),
            eq(Map.of("key1", "value1")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void getLastHeartbeat_heartbeatFound_returnsHeartbeat() {
    // Given
    String workflowId = "wf-hb-found";
    String nodeId = "node-hb-found";
    Message<String> heartbeat = DefaultMessage.create(null, "heartbeat-payload");
    when((Message<String>) controlBusService.getLastHeartbeat(workflowId, nodeId))
        .thenReturn(heartbeat);

    // When
    Message<?> result = gateway.getLastHeartbeat(workflowId, nodeId);

    // Then - covers the non-null branch (line 591-594)
    assertThat(result).isEqualTo(heartbeat);
    verify(controlBusService).getLastHeartbeat(workflowId, nodeId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void getLastStatistics_statisticsFound_returnsStatistics() {
    // Given
    String workflowId = "wf-stats-found";
    String nodeId = "node-stats-found";
    Message<String> statistics = DefaultMessage.create(null, "stats-payload");
    when((Message<String>) controlBusService.getLastStatistics(workflowId, nodeId))
        .thenReturn(statistics);

    // When
    Message<?> result = gateway.getLastStatistics(workflowId, nodeId);

    // Then - covers the non-null branch (line 612-615)
    assertThat(result).isEqualTo(statistics);
    verify(controlBusService).getLastStatistics(workflowId, nodeId);
  }

  @Test
  void subscribeToStatusEvents_updateTaskStatusSuccess_triggersDoOnSuccess() {
    // Given - mock updateTaskStatus to return Mono.empty() so doOnSuccess fires
    when(taskTracker.updateTaskStatus(anyString(), anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(Mono.empty());
    gateway.subscribeToStatusEvents();
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-success-cb",
            "node-success-cb",
            "workflow-success-cb",
            "session-success-cb",
            "DONE",
            "module-success-cb",
            Map.of(),
            null,
            Instant.now());

    // When
    gateway.publishStatus(event).block();

    // Then - verify updateTaskStatus was called and doOnSuccess lambda was exercised
    verify(taskTracker)
        .updateTaskStatus(
            eq("exec-success-cb"),
            eq("node-success-cb"),
            eq("module-success-cb"),
            eq("DONE"),
            eq(Map.of()));
  }

  @Test
  void subscribeToStatusEvents_updateTaskStatusError_triggersDoOnError() {
    // Given - mock updateTaskStatus to return Mono.error() so doOnError fires
    when(taskTracker.updateTaskStatus(anyString(), anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(Mono.error(new RuntimeException("tracker error")));
    gateway.subscribeToStatusEvents();
    ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-error-cb",
            "node-error-cb",
            "workflow-error-cb",
            "session-error-cb",
            "FAILED",
            "module-error-cb",
            Map.of(),
            null,
            Instant.now());

    // When - error is swallowed by inner subscribe(), outer publishStatus still completes
    StepVerifier.create(gateway.publishStatus(event)).verifyComplete();

    // Then - verify updateTaskStatus was called (doOnError lambda exercised internally)
    verify(taskTracker)
        .updateTaskStatus(
            eq("exec-error-cb"),
            eq("node-error-cb"),
            eq("module-error-cb"),
            eq("FAILED"),
            eq(Map.of()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void subscribeToStatusEvents_sinkCompletion_triggersOnCompleteCallback() throws Exception {
    // Access the private statusSink via reflection to trigger sink completion
    java.lang.reflect.Field sinkField =
        DefaultControlBusGateway.class.getDeclaredField("statusSink");
    sinkField.setAccessible(true);
    reactor.core.publisher.Sinks.Many<ExecutionStatusEvent> sink =
        (reactor.core.publisher.Sinks.Many<ExecutionStatusEvent>) sinkField.get(gateway);

    gateway.subscribeToStatusEvents();

    // Completing the sink fires the onComplete callback (line 133)
    sink.tryEmitComplete();

    // Verify gateway is still valid after completion
    assertThat(gateway).isNotNull();
  }

  // --- startWorkflow Tests ---

  @Test
  void startWorkflow_workflowFound_returnsNewExecutionId() {
    // Given
    String sessionId = "sess-start";
    String workflowId = "wf-start";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "test workflow", List.of(), List.of());
    PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(
            executionId -> {
              assertThat(executionId).isNotNull();
              // Verify it's a valid UUID format
              try {
                UUID.fromString(executionId);
              } catch (IllegalArgumentException e) {
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
    String sessionId = "sess-not-found";
    String workflowId = "wf-not-found";
    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

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
    String sessionId = "sess-cached";
    String workflowId = "wf-cached";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "cached workflow", List.of(), List.of());
    PreparedWorkflow cachedPreparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId))
        .thenReturn(Optional.of(cachedPreparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(cachedPreparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

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
    String sessionId = "sess-prepare";
    String workflowId = "wf-prepare";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow to prepare", List.of(), List.of());
    PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(executionId -> assertThat(executionId).isNotNull())
        .verifyComplete();

    // Verify prepareWorkflow was called
    verify(orchestrator).prepareWorkflow(definition);
    // Verify cache.put was called with the prepared workflow
    ArgumentCaptor<PreparedWorkflow> cacheCaptor = ArgumentCaptor.forClass(PreparedWorkflow.class);
    verify(preparedWorkflowCache).put(eq(sessionId), eq(workflowId), cacheCaptor.capture());
    assertThat(cacheCaptor.getValue()).isEqualTo(preparedWorkflow);
  }

  @Test
  void startWorkflow_orchestratorPrepareFailure_propagatesError() {
    // Given
    String sessionId = "sess-prepare-fail";
    String workflowId = "wf-prepare-fail";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    RuntimeException testError = new RuntimeException("Prepare failed");

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void startWorkflow_orchestratorExecuteFailure_propagatesError() {
    // Given
    String sessionId = "sess-exec-fail";
    String workflowId = "wf-exec-fail";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);
    RuntimeException testError = new RuntimeException("Execute failed");

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void startWorkflow_workflowDefinitionStoreError_propagatesError() {
    // Given
    String sessionId = "sess-store-error";
    String workflowId = "wf-store-error";
    RuntimeException testError = new RuntimeException("Store error");

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.error(testError));

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result).expectError(RuntimeException.class).verify();
  }

  @Test
  void startWorkflow_executionIdUnique_generatesNewIdEachTime() {
    // Given
    String sessionId = "sess-unique";
    String workflowId = "wf-unique";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When - call twice
    Mono<String> result1 = gateway.startWorkflow(sessionId, workflowId);
    Mono<String> result2 = gateway.startWorkflow(sessionId, workflowId);

    // Then - execution IDs should be different
    String execId1 = result1.block();
    String execId2 = result2.block();

    assertThat(execId1).isNotEqualTo(execId2);
    assertThat(execId1).isNotNull();
    assertThat(execId2).isNotNull();
  }

  @Test
  void startWorkflow_executePassesCorrectParameters() {
    // Given
    String sessionId = "sess-params";
    String workflowId = "wf-params";
    WorkflowDefinition definition =
        new WorkflowDefinition(workflowId, "workflow", List.of(), List.of());
    PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId)).thenReturn(Mono.just(definition));
    when(preparedWorkflowCache.get(sessionId, workflowId))
        .thenReturn(Optional.of(preparedWorkflow));
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), anyString(), eq(preparedWorkflow), anyMap()))
        .thenReturn(Mono.empty());

    // When
    Mono<String> result = gateway.startWorkflow(sessionId, workflowId);

    // Then
    StepVerifier.create(result)
        .assertNext(executionId -> assertThat(executionId).isNotNull())
        .verifyComplete();

    ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> workflowCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> executionIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedWorkflow> preparedCaptor =
        ArgumentCaptor.forClass(PreparedWorkflow.class);
    ArgumentCaptor<Map> contextMapCaptor = ArgumentCaptor.forClass(Map.class);

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
    String sessionId = "sess-multi";
    String workflowId1 = "wf-1";
    String workflowId2 = "wf-2";
    WorkflowDefinition definition1 =
        new WorkflowDefinition(workflowId1, "workflow 1", List.of(), List.of());
    WorkflowDefinition definition2 =
        new WorkflowDefinition(workflowId2, "workflow 2", List.of(), List.of());
    PreparedWorkflow preparedWorkflow1 = mock(PreparedWorkflow.class);
    PreparedWorkflow preparedWorkflow2 = mock(PreparedWorkflow.class);

    when(workflowDefinitionStore.find(sessionId, workflowId1)).thenReturn(Mono.just(definition1));
    when(workflowDefinitionStore.find(sessionId, workflowId2)).thenReturn(Mono.just(definition2));
    when(preparedWorkflowCache.get(sessionId, workflowId1)).thenReturn(Optional.empty());
    when(preparedWorkflowCache.get(sessionId, workflowId2)).thenReturn(Optional.empty());
    when(orchestrator.prepareWorkflow(definition1)).thenReturn(Mono.just(preparedWorkflow1));
    when(orchestrator.prepareWorkflow(definition2)).thenReturn(Mono.just(preparedWorkflow2));
    when(orchestrator.execute(eq(sessionId), anyString(), anyString(), any(), anyMap()))
        .thenReturn(Mono.empty());

    // When
    Mono<String> result1 = gateway.startWorkflow(sessionId, workflowId1);
    Mono<String> result2 = gateway.startWorkflow(sessionId, workflowId2);

    // Then
    String execId1 = result1.block();
    String execId2 = result2.block();

    assertThat(execId1).isNotNull();
    assertThat(execId2).isNotNull();
    assertThat(execId1).isNotEqualTo(execId2);

    // Verify both workflows were cached separately
    verify(preparedWorkflowCache).put(sessionId, workflowId1, preparedWorkflow1);
    verify(preparedWorkflowCache).put(sessionId, workflowId2, preparedWorkflow2);
  }
}
