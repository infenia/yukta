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
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RestartFromNodeCommandProcessorTest {

  @Mock private ExecutionControlRegistry registry;
  @Mock private WorkflowOrchestrator orchestrator;
  @Mock private NodeCheckpointStore checkpointStore;
  @Mock private DefaultTaskTrackerService taskTracker;
  @Mock private ExecutionControl executionControl;
  @Mock private PreparedWorkflow preparedWorkflow;
  @Mock private Message<?> checkpointMessage;

  @InjectMocks private RestartFromNodeCommandProcessor processor;

  @Test
  void canProcess_restartFromNodeCommand_returnsTrue() {
    // Given
    ExecutionControlCommand command = new RestartFromNodeCommand("exec-1", "node-1");

    // When
    boolean actualResult = processor.canProcess(command);

    // Then
    assertThat(actualResult).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    // Given
    ExecutionControlCommand command = new PauseWorkflowCommand("exec-1");

    // When
    boolean actualResult = processor.canProcess(command);

    // Then
    assertThat(actualResult).isFalse();
  }

  @Test
  void process_noParentNodes_restartsWithEmptyCheckpoints() {
    // Given
    String executionId = "exec-restart-from-no-parents";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    Sinks.One<Void> safeStopSink = Sinks.one();
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    // No parent nodes for fromNodeId
    when(preparedWorkflow.parentsList()).thenReturn(Map.of());
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            eq(Map.of())))
        .thenReturn(Mono.empty());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);

    ArgumentCaptor<String> newExecIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(orchestrator)
        .restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            newExecIdCaptor.capture(),
            eq(preparedWorkflow),
            eq(fromNodeId),
            eq(Map.of()));
    String actualNewExecutionId = newExecIdCaptor.getValue();
    assertThat(actualNewExecutionId).isNotEqualTo(executionId).isNotBlank();
    verify(taskTracker).emitWorkflowStatusEvent(actualNewExecutionId, "RUNNING");
  }

  @Test
  void process_withParentNodes_loadsCheckpointsAndRestartsFromNode() {
    // Given
    String executionId = "exec-restart-from-parents";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    String parentNodeId = "parent-node";
    Sinks.One<Void> safeStopSink = Sinks.one();
    WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    when(checkpointStore.get(executionId, parentNodeId)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNodeId);
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.empty());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(checkpointStore).get(executionId, parentNodeId);
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
  }

  @Test
  void process_checkpointStoreFails_continuesWithAvailableCheckpoints() {
    // Given
    String executionId = "exec-checkpoint-fail";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    String parentNodeId = "parent-node";
    Sinks.One<Void> safeStopSink = Sinks.one();
    WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    // Checkpoint store fails — should be handled gracefully
    when(checkpointStore.get(executionId, parentNodeId))
        .thenReturn(Mono.error(new RuntimeException("Checkpoint not found")));
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            eq(Map.of())))
        .thenReturn(Mono.empty());

    // When
    var result = processor.process(command);

    // Then — checkpoint failure is swallowed, orchestrator called with empty checkpoints
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void process_executionNotFound_completesEmptyWithoutError() {
    // Given
    String executionId = "exec-not-found";
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, "node-1");
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    var result = processor.process(command);

    // Then — outer onErrorResume swallows the error
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(20);
  }

  @Test
  void process_orchestratorFailsDuringRestart_unregistersButDoesNotEmitTrackingEvent() {
    // Given
    String executionId = "exec-orch-fails";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    String parentNodeId = "parent-node";
    Sinks.One<Void> safeStopSink = Sinks.one();
    WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    when(checkpointStore.get(executionId, parentNodeId)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNodeId);
    // Orchestrator fails
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.error(new RuntimeException("Orchestrator restart failed")));

    // When
    var result = processor.process(command);

    // Then — error is swallowed by onErrorResume at line 131-139
    StepVerifier.create(result).verifyComplete();
    // Side effects (unregister, clear) still happen before orchestrator is called
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
    // doOnSuccess is not reached, so tracking event is NOT emitted
    verify(taskTracker, org.mockito.Mockito.never())
        .emitWorkflowStatusEvent(any(String.class), any(String.class));
  }

  @Test
  void process_withParentNodes_verifyTaskTrackerEmitsWorkflowStatusEvent() {
    // Given
    String executionId = "exec-verify-tracker";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    String parentNodeId = "parent-node";
    Sinks.One<Void> safeStopSink = Sinks.one();
    WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    when(checkpointStore.get(executionId, parentNodeId)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNodeId);
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.empty());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();

    // Capture and verify the new execution ID passed to task tracker
    ArgumentCaptor<String> newExecIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(taskTracker).emitWorkflowStatusEvent(newExecIdCaptor.capture(), eq("RUNNING"));
    String actualNewExecutionId = newExecIdCaptor.getValue();
    assertThat(actualNewExecutionId).isNotEqualTo(executionId).isNotBlank();
  }

  @Test
  void process_multipleParentNodes_oneCheckpointFailsPartial() {
    // Given
    String executionId = "exec-partial-checkpoints";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    String parentNode1Id = "parent-1";
    String parentNode2Id = "parent-2";
    Sinks.One<Void> safeStopSink = Sinks.one();
    WorkflowNode parentNode1 = new WorkflowNode(parentNode1Id, "processor", Map.of());
    WorkflowNode parentNode2 = new WorkflowNode(parentNode2Id, "processor", Map.of());
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList())
        .thenReturn(Map.of(fromNodeId, List.of(parentNode1, parentNode2)));
    // First parent succeeds
    when(checkpointStore.get(executionId, parentNode1Id)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNode1Id);
    // Second parent fails — onErrorResume produces Mono.empty()
    when(checkpointStore.get(executionId, parentNode2Id))
        .thenReturn(Mono.error(new RuntimeException("Checkpoint not found")));
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.empty());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    // Verify orchestrator was called (checkpoints should contain only the successful one)
    verify(orchestrator)
        .restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any());
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
  }

  @Test
  void process_safeStopSinkEmitFails_continuesWithRestart() {
    // Given
    String executionId = "exec-sink-emit-fail";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    String fromNodeId = "node-target";
    String parentNodeId = "parent-node";
    WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    RestartFromNodeCommand command = new RestartFromNodeCommand(executionId, fromNodeId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    when(checkpointStore.get(executionId, parentNodeId)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNodeId);
    // Mock safeStopSink that throws when emitEmpty is called
    Sinks.One<Void> failingSink = Sinks.one();
    failingSink.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
    when(executionControl.safeStopSink()).thenReturn(failingSink);
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            any(String.class),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.empty());

    // When
    var result = processor.process(command);

    // Then — the inner onErrorResume (lines 131-139) catches the sink emit failure
    StepVerifier.create(result).verifyComplete();
  }
}
