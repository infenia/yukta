// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
class ResumeWorkflowCommandProcessorTest {

  /** Registry for execution control. */
  @Mock private ExecutionControlStore registry;

  /** Task tracker service. */
  @Mock private TaskTrackerService taskTracker;

  /** Execution control instance. */
  @Mock private ExecutionControl executionControl;

  /** Global pause valve. */
  @Mock private ReactiveControlValve globalPauseValve;

  @InjectMocks private ResumeWorkflowCommandProcessor processor;

  @Test
  void process_executionFound_resumesValveAndEmitsEvent() {
    // Given
    final String executionId = "exec-resume-wf";
    final ResumeWorkflowCommand command = new ResumeWorkflowCommand(executionId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.globalPauseValve()).thenReturn(globalPauseValve);

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(globalPauseValve).resume();
    verify(taskTracker).emitControlStateEvent(executionId, ControlState.RESUMED);
  }

  @Test
  void process_executionNotFound_errorWithIllegalArgumentException() {
    // Given
    final String executionId = "exec-not-found";
    final ResumeWorkflowCommand command = new ResumeWorkflowCommand(executionId);
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
  }

  @Test
  void process_globalPauseValveIsNull_errorWithIllegalStateException() {
    // Given
    final String executionId = "exec-null-valve";
    final ResumeWorkflowCommand command = new ResumeWorkflowCommand(executionId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.globalPauseValve()).thenReturn(null);

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalStateException
                    && e.getMessage().contains("Global pause valve not initialized"))
        .verify();
  }
}
