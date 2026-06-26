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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class StopNodeCommandProcessorTest {

  @Mock private ExecutionControlRegistry registry;
  @Mock private DefaultTaskTrackerService taskTracker;
  @Mock private ExecutionControl executionControl;

  @InjectMocks private StopNodeCommandProcessor processor;

  @Test
  void canProcess_stopNodeCommand_returnsTrue() {
    // Given
    ExecutionControlCommand command = new StopNodeCommand("exec-1", "node-1", false, null);

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
  void process_immediateStop_usesImmediateStopSinkAndEmitsEvent() {
    // Given
    String executionId = "exec-immediate-stop";
    String nodeId = "node-1";
    Sinks.One<Void> immediateSink = Sinks.one();
    StopNodeCommand command = new StopNodeCommand(executionId, nodeId, true, "immediate stop");
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeImmediateStopSinks()).thenReturn(Map.of(nodeId, immediateSink));

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "NODE_STOPPED");
  }

  @Test
  void process_safeStop_usesSafeStopSinkAndEmitsEvent() {
    // Given
    String executionId = "exec-safe-stop";
    String nodeId = "node-1";
    Sinks.One<Void> safeSink = Sinks.one();
    StopNodeCommand command = new StopNodeCommand(executionId, nodeId, false, "safe stop");
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeSafeStopSinks()).thenReturn(Map.of(nodeId, safeSink));

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "NODE_STOPPED");
  }

  @Test
  void process_executionNotFound_errorWithIllegalArgumentException() {
    // Given
    String executionId = "exec-not-found";
    StopNodeCommand command = new StopNodeCommand(executionId, "node-1", false, null);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.empty());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Execution not found: " + executionId))
        .verify();
  }

  @Test
  void process_nodeNotFoundForSafeStop_errorWithIllegalArgumentException() {
    // Given
    String executionId = "exec-1";
    String nodeId = "unknown-node";
    StopNodeCommand command = new StopNodeCommand(executionId, nodeId, false, null);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeSafeStopSinks()).thenReturn(Map.of());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Node not found or not stoppable: " + nodeId))
        .verify();
  }

  @Test
  void process_nodeNotFoundForImmediateStop_errorWithIllegalArgumentException() {
    // Given
    String executionId = "exec-1";
    String nodeId = "unknown-node";
    StopNodeCommand command = new StopNodeCommand(executionId, nodeId, true, null);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeImmediateStopSinks()).thenReturn(Map.of());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Node not found or not stoppable: " + nodeId))
        .verify();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(15);
  }
}
