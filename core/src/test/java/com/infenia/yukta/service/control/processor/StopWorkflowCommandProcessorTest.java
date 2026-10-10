// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
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
    verify(safeStopSink).tryEmitEmpty();
  }

  @Test
  void process_stoppedTwice_doesNotThrow() {
    // Given
    final String executionId = "exec-stop-twice";
    final Sinks.One<Void> realSink = Sinks.one();
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.safeStopSink()).thenReturn(realSink);
    final StopWorkflowCommand command = new StopWorkflowCommand(executionId, "again");

    // When-Then
    StepVerifier.create(processor.process(command)).verifyComplete();
    StepVerifier.create(processor.process(command)).verifyComplete();
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
    verify(safeStopSink).tryEmitEmpty();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    final int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(20);
  }
}
