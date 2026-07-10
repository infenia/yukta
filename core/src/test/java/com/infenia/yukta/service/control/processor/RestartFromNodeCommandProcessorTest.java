// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LinguisticNaming",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports"
})
class RestartFromNodeCommandProcessorTest {

  @Mock private ExecutionControlRegistry registry;
  @Mock private WorkflowOrchestrator orchestrator;
  @Mock private NodeCheckpointStore checkpointStore;
  @Mock private RestartCompletionSink completionSink;
  @Mock private ExecutionControl executionControl;
  @Mock private PreparedWorkflow preparedWorkflow;
  @Mock private Message<?> checkpointMessage;

  @InjectMocks private RestartFromNodeCommandProcessor processor;

  @Test
  void canProcess_restartFromNodeCommand_returnsTrue() {
    final ExecutionControlCommand command = new RestartFromNodeCommand("exec-1", "node-1", "new-1");
    assertThat(processor.canProcess(command)).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    final ExecutionControlCommand command = new PauseWorkflowCommand("exec-1");
    assertThat(processor.canProcess(command)).isFalse();
  }

  @Test
  void process_noParentNodes_detachesRestartAndReportsSuccessImmediately() {
    // Given
    final String executionId = "exec-restart-from-no-parents";
    final String newExecutionId = "new-exec-restart-from-no-parents";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String fromNodeId = "node-target";
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, fromNodeId, newExecutionId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of());
    // Never completes — proves the processor does not wait for it.
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            eq(newExecutionId),
            eq(preparedWorkflow),
            eq(fromNodeId),
            eq(Map.of())))
        .thenReturn(Mono.never());

    // When
    final var result = processor.process(command);

    // Then — verify atomic handoff and detached execution
    StepVerifier.create(result).verifyComplete();
    assertThat(safeStopSink.currentSubscriberCount()).isZero();
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
    verify(completionSink).completeRestartSuccess(newExecutionId);
    verify(orchestrator)
        .restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            eq(newExecutionId),
            eq(preparedWorkflow),
            eq(fromNodeId),
            eq(Map.of()));
  }

  @Test
  void process_withParentNodes_loadsCheckpointsAndDetachesRestart() {
    // Given
    final String executionId = "exec-restart-from-parents";
    final String newExecutionId = "new-exec-restart-from-parents";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String fromNodeId = "node-target";
    final String parentNodeId = "parent-node";
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, fromNodeId, newExecutionId);

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
            eq(newExecutionId),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.never());

    // When
    final var result = processor.process(command);

    // Then — verify checkpoint loading and atomic handoff
    StepVerifier.create(result).verifyComplete();
    assertThat(safeStopSink.currentSubscriberCount()).isZero();
    verify(checkpointStore).get(executionId, parentNodeId);
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
    verify(completionSink).completeRestartSuccess(newExecutionId);
  }

  @Test
  void process_checkpointStoreFails_continuesWithAvailableCheckpoints() {
    // Given
    final String executionId = "exec-checkpoint-fail";
    final String newExecutionId = "new-exec-checkpoint-fail";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String fromNodeId = "node-target";
    final String parentNodeId = "parent-node";
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, fromNodeId, newExecutionId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    when(checkpointStore.get(executionId, parentNodeId))
        .thenReturn(Mono.error(new RuntimeException("Checkpoint not found")));
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            eq(newExecutionId),
            eq(preparedWorkflow),
            eq(fromNodeId),
            eq(Map.of())))
        .thenReturn(Mono.never());

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(completionSink).completeRestartSuccess(newExecutionId);
  }

  @Test
  void process_executionNotFound_reportsFailureAndCompletesEmpty() {
    // Given
    final String executionId = "exec-not-found";
    final String newExecutionId = "new-exec-not-found";
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, "node-1", newExecutionId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(completionSink)
        .completeRestartFailure(eq(newExecutionId), any(IllegalArgumentException.class));
  }

  @Test
  void getPriority_returnsCorrectValue() {
    assertThat(processor.getPriority()).isEqualTo(20);
  }

  @Test
  void process_orchestratorFailsAsynchronously_doesNotReportFailureViaCompletionSink() {
    // Given: detachment means an async orchestrator failure is not observable by process()'s
    // own Mono — completeRestartSuccess was already called at subscribe time.
    final String executionId = "exec-orch-fails";
    final String newExecutionId = "new-exec-orch-fails";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String fromNodeId = "node-target";
    final String parentNodeId = "parent-node";
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, fromNodeId, newExecutionId);

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
            eq(newExecutionId),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.error(new RuntimeException("Orchestrator restart failed")));

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
    verify(completionSink, timeout(1000)).completeRestartSuccess(newExecutionId);
    verify(completionSink, never()).completeRestartFailure(eq(newExecutionId), any());
  }

  @Test
  void process_multipleParentNodes_oneCheckpointFailsPartial() {
    // Given
    final String executionId = "exec-partial-checkpoints";
    final String newExecutionId = "new-exec-partial-checkpoints";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String fromNodeId = "node-target";
    final String parentNode1Id = "parent-1";
    final String parentNode2Id = "parent-2";
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final WorkflowNode parentNode1 = new WorkflowNode(parentNode1Id, "processor", Map.of());
    final WorkflowNode parentNode2 = new WorkflowNode(parentNode2Id, "processor", Map.of());
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, fromNodeId, newExecutionId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(preparedWorkflow.parentsList())
        .thenReturn(Map.of(fromNodeId, List.of(parentNode1, parentNode2)));
    when(checkpointStore.get(executionId, parentNode1Id)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNode1Id);
    when(checkpointStore.get(executionId, parentNode2Id))
        .thenReturn(Mono.error(new RuntimeException("Checkpoint not found")));
    when(orchestrator.restartFromNode(
            eq(sessionId),
            eq(workflowId),
            eq(executionId),
            eq(newExecutionId),
            eq(preparedWorkflow),
            eq(fromNodeId),
            any()))
        .thenReturn(Mono.never());

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(registry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
    verify(completionSink).completeRestartSuccess(newExecutionId);
  }

  @Test
  void process_safeStopSinkEmitFails_reportsFailure() {
    // Given
    final String executionId = "exec-sink-emit-fail";
    final String newExecutionId = "new-exec-sink-emit-fail";
    final String fromNodeId = "node-target";
    final String parentNodeId = "parent-node";
    final WorkflowNode parentNode = new WorkflowNode(parentNodeId, "processor", Map.of());
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(executionId, fromNodeId, newExecutionId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of(fromNodeId, List.of(parentNode)));
    when(checkpointStore.get(executionId, parentNodeId)).thenReturn(Mono.just(checkpointMessage));
    when(checkpointMessage.getSourceNodeId()).thenReturn(parentNodeId);
    final Sinks.One<Void> failingSink = Sinks.one();
    failingSink.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
    when(executionControl.safeStopSink()).thenReturn(failingSink);

    // When
    final var result = processor.process(command);

    // Then — the outer onErrorResume catches the sink emit failure and reports it
    StepVerifier.create(result).verifyComplete();
    verify(registry, never()).unregister(executionId);
    verify(orchestrator, never()).restartFromNode(any(), any(), any(), any(), any(), any(), any());
    verify(completionSink).completeRestartFailure(eq(newExecutionId), any(RuntimeException.class));
  }
}
