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
class RestartCommandProcessorTest {

  @Mock private ExecutionControlRegistry registry;
  @Mock private WorkflowOrchestrator orchestrator;
  @Mock private DefaultTaskTrackerService taskTracker;
  @Mock private ExecutionControl executionControl;
  @Mock private PreparedWorkflow preparedWorkflow;

  @InjectMocks private RestartCommandProcessor processor;

  @Test
  void canProcess_restartCommand_returnsTrue() {
    // Given
    ExecutionControlCommand command = new RestartCommand("exec-1");

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
  void process_executionFound_unregistersAndRestartsWorkflow() {
    // Given
    String executionId = "exec-restart";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    Map<String, Object> payload = Map.of("key", "value");
    Sinks.One<Void> safeStopSink = Sinks.one();
    RestartCommand command = new RestartCommand(executionId);

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
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(registry).unregister(executionId);

    ArgumentCaptor<String> newExecIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(orchestrator)
        .execute(
            eq(sessionId),
            eq(workflowId),
            newExecIdCaptor.capture(),
            eq(preparedWorkflow),
            eq(payload));
    String actualNewExecutionId = newExecIdCaptor.getValue();
    assertThat(actualNewExecutionId).isNotEqualTo(executionId).isNotBlank();
    verify(taskTracker).emitWorkflowStatusEvent(actualNewExecutionId, "RUNNING");
  }

  @Test
  void process_executionNotFound_completesEmptyWithoutError() {
    // Given
    String executionId = "exec-not-found";
    RestartCommand command = new RestartCommand(executionId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    var result = processor.process(command);

    // Then — error is swallowed by onErrorResume, Mono completes empty
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void process_orchestratorFails_completesEmptyWithoutError() {
    // Given
    String executionId = "exec-orch-fail";
    String sessionId = "session-1";
    String workflowId = "workflow-1";
    Sinks.One<Void> safeStopSink = Sinks.one();
    RestartCommand command = new RestartCommand(executionId);

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
    var result = processor.process(command);

    // Then — inner onErrorResume swallows the error
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(20);
  }
}
