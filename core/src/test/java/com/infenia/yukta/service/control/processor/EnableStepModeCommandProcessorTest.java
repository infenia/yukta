// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.Map;
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
class EnableStepModeCommandProcessorTest {

  @Mock private ExecutionControlStore registry;
  @Mock private TaskTrackerService taskTracker;
  @Mock private ExecutionControl executionControl;

  @InjectMocks private EnableStepModeCommandProcessor processor;

  @Test
  void process_nodeFound_enablesValveStepModeAndEmitsEvent() {
    // Given
    final String executionId = "exec-enable-step";
    final String nodeId = "node-1";
    final ReactiveControlValve valve = new ReactiveControlValve();
    final EnableStepModeCommand command = new EnableStepModeCommand(executionId, nodeId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodePauseValves()).thenReturn(Map.of(nodeId, valve));

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    assertThat(valve.isStepMode()).isTrue();
    assertThat(valve.isPaused()).isTrue();
    verify(taskTracker).emitControlStateEvent(executionId, ControlState.STEP_MODE_ENABLED);
  }

  @Test
  void process_executionNotFound_errorWithIllegalArgumentException() {
    // Given
    final String executionId = "exec-not-found";
    final EnableStepModeCommand command = new EnableStepModeCommand(executionId, "node-1");
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
  void process_nodeNotFound_errorWithIllegalArgumentException() {
    // Given
    final String executionId = "exec-1";
    final String nodeId = "unknown-node";
    final EnableStepModeCommand command = new EnableStepModeCommand(executionId, nodeId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodePauseValves()).thenReturn(Map.of());

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage()
                        .contains("Node not found or does not support step mode: " + nodeId))
        .verify();
  }
}
