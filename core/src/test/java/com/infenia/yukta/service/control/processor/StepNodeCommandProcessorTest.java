// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
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
class StepNodeCommandProcessorTest {

  /** Registry for execution control. */
  @Mock private ExecutionControlStore registry;

  /** Task tracker service. */
  @Mock private TaskTrackerService taskTracker;

  /** Execution control instance. */
  @Mock private ExecutionControl executionControl;

  /** Step sink for control messages. */
  @InjectMocks private StepNodeCommandProcessor processor;

  @Test
  void process_nodeInStepMode_stepsValveAndEmitsEvent() {
    // Given
    final String executionId = "exec-step";
    final String nodeId = "node-1";
    final StepNodeCommand command = new StepNodeCommand(executionId, nodeId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    final ReactiveControlValve valve = new ReactiveControlValve();
    valve.enableStepMode();
    when(executionControl.nodePauseValves()).thenReturn(Map.of(nodeId, valve));

    // When
    final var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(taskTracker).emitControlStateEvent(executionId, ControlState.NODE_STEPPED);
  }

  @Test
  void process_nodeNotInStepMode_errorWithIllegalStateException() {
    // Given
    final String executionId = "exec-1";
    final String nodeId = "node-1";
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodePauseValves()).thenReturn(Map.of(nodeId, new ReactiveControlValve()));

    // When
    final var result = processor.process(new StepNodeCommand(executionId, nodeId));

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalStateException
                    && e.getMessage().contains("Node is not in step mode: " + nodeId))
        .verify();
  }

  @Test
  void process_executionNotFound_errorWithIllegalArgumentException() {
    // Given
    final String executionId = "exec-not-found";
    final StepNodeCommand command = new StepNodeCommand(executionId, "node-1");
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
    final StepNodeCommand command = new StepNodeCommand(executionId, nodeId);
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
                        .contains("Node not found or does not support stepping: " + nodeId))
        .verify();
  }
}
