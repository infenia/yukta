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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.LinguisticNaming"})
class StopWorkflowCommandProcessorTest {

  /** Registry for execution control. */
  @Mock private ExecutionControlRegistry registry;

  /** Task tracker service. */
  @Mock private DefaultTaskTrackerService taskTracker;

  /** Execution control instance. */
  @Mock private ExecutionControl executionControl;

  /** Safe stop sink. */
  @Mock private Sinks.One<Void> safeStopSink;

  @InjectMocks private StopWorkflowCommandProcessor processor;

  @Test
  void canProcess_stopWorkflowCommand_returnsTrue() {
    // Given
    final ExecutionControlCommand command =
        new StopWorkflowCommand("exec-1", "User initiated stop");

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
  void process_executionFound_emitsSafeStopAndStatusEvent() {
    // Given
    final String executionId = "exec-stop-workflow";
    final String reason = "User initiated stop";
    final StopWorkflowCommand command = new StopWorkflowCommand(executionId, reason);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(safeStopSink).emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "WORKFLOW_STOPPED");
  }

  @Test
  void process_executionNotFound_throwsIllegalArgumentException() {
    // Given
    final String executionId = "exec-not-found";
    final StopWorkflowCommand command = new StopWorkflowCommand(executionId, "Test reason");
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Execution not found: " + executionId))
        .verify();
    verify(taskTracker, never()).emitWorkflowStatusEvent(executionId, "WORKFLOW_STOPPED");
  }

  @Test
  void process_withNullReason_emitsSafeStopAndStatusEvent() {
    // Given
    final String executionId = "exec-stop-null-reason";
    final StopWorkflowCommand command = new StopWorkflowCommand(executionId, null);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.safeStopSink()).thenReturn(safeStopSink);

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(safeStopSink).emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "WORKFLOW_STOPPED");
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    final int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(20);
  }
}
