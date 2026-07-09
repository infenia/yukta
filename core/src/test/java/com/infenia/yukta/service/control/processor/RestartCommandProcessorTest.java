// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
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
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.CommentRequired", "PMD.LinguisticNaming"})
class RestartCommandProcessorTest {

  /** Registry for execution control. */
  @Mock private ExecutionControlRegistry registry;

  /** Workflow orchestrator. */
  @Mock private WorkflowOrchestrator orchestrator;

  /** Task tracker service. */
  @Mock private DefaultTaskTrackerService taskTracker;

  /** Execution control instance. */
  @Mock private ExecutionControl executionControl;

  /** Prepared workflow. */
  @Mock private PreparedWorkflow preparedWorkflow;

  @InjectMocks private RestartCommandProcessor processor;

  @Test
  void canProcess_restartCommand_returnsTrue() {
    // Given
    final ExecutionControlCommand command = new RestartCommand("exec-1");

    // When
    final boolean actualResult = processor.canProcess(command);

    // Then
    assertThat(actualResult).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    // Given
    final ExecutionControlCommand command = new PauseWorkflowCommand("exec-1");

    // When
    final boolean actualResult = processor.canProcess(command);

    // Then
    assertThat(actualResult).isFalse();
  }

  @Test
  void process_executionFound_unregistersAndRestartsWorkflow() {
    // Given
    final String executionId = "exec-restart";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final Map<String, Object> payload = Map.of("key", "value");
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final RestartCommand command = new RestartCommand(executionId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.payload()).thenReturn(payload);
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), any(String.class), eq(preparedWorkflow), eq(payload)))
        .thenReturn(Mono.empty());

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(registry).unregister(executionId);

    final ArgumentCaptor<String> newExecIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(orchestrator)
        .execute(
            eq(sessionId),
            eq(workflowId),
            newExecIdCaptor.capture(),
            eq(preparedWorkflow),
            eq(payload));
    final String actualNewExecutionId = newExecIdCaptor.getValue();
    assertThat(actualNewExecutionId).isNotEqualTo(executionId).isNotBlank();
    verify(taskTracker).emitWorkflowStatusEvent(actualNewExecutionId, "RUNNING");
  }

  @Test
  void process_executionNotFound_completesEmptyWithoutError() {
    // Given
    final String executionId = "exec-not-found";
    final RestartCommand command = new RestartCommand(executionId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final var result = processor.process(command);

    // Then — error is swallowed by onErrorResume, Mono completes empty
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void process_orchestratorFails_completesEmptyWithoutError() {
    // Given
    final String executionId = "exec-orch-fail";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final RestartCommand command = new RestartCommand(executionId);

    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.executionId()).thenReturn(executionId);
    when(executionControl.sessionId()).thenReturn(sessionId);
    when(executionControl.workflowId()).thenReturn(workflowId);
    when(executionControl.prepared()).thenReturn(preparedWorkflow);
    when(executionControl.payload()).thenReturn(Map.of());
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);
    when(orchestrator.execute(
            eq(sessionId), eq(workflowId), any(String.class), eq(preparedWorkflow), eq(Map.of())))
        .thenReturn(Mono.error(new RuntimeException("Orchestrator failure")));

    // When
    final var result = processor.process(command);

    // Then — inner onErrorResume swallows the error
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    final int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(20);
  }
}
