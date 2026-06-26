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
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SkipNodeCommandProcessorTest {

  @Mock private ExecutionControlRegistry registry;
  @Mock private DefaultTaskTrackerService taskTracker;
  @Mock private ExecutionControl executionControl;

  @InjectMocks private SkipNodeCommandProcessor processor;

  @Test
  void canProcess_skipNodeCommand_returnsTrue() {
    // Given
    ExecutionControlCommand command = new SkipNodeCommand("exec-1", "node-1", true);

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
  void process_skipTrue_setsSkipFlagAndEmitsNodeSkipped() {
    // Given
    String executionId = "exec-skip";
    String nodeId = "node-1";
    AtomicBoolean skipFlag = new AtomicBoolean(false);
    SkipNodeCommand command = new SkipNodeCommand(executionId, nodeId, true);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeSkipFlags()).thenReturn(Map.of(nodeId, skipFlag));

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    assertThat(skipFlag.get()).isTrue();
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "NODE_SKIPPED");
  }

  @Test
  void process_skipFalse_clearsSkipFlagAndEmitsNodeUnskipped() {
    // Given
    String executionId = "exec-unskip";
    String nodeId = "node-1";
    AtomicBoolean skipFlag = new AtomicBoolean(true);
    SkipNodeCommand command = new SkipNodeCommand(executionId, nodeId, false);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeSkipFlags()).thenReturn(Map.of(nodeId, skipFlag));

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    assertThat(skipFlag.get()).isFalse();
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "NODE_UNSKIPPED");
  }

  @Test
  void process_executionNotFound_errorWithIllegalArgumentException() {
    // Given
    String executionId = "exec-not-found";
    SkipNodeCommand command = new SkipNodeCommand(executionId, "node-1", true);
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
  void process_nodeNotFound_errorWithIllegalArgumentException() {
    // Given
    String executionId = "exec-1";
    String nodeId = "unknown-node";
    SkipNodeCommand command = new SkipNodeCommand(executionId, nodeId, true);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodeSkipFlags()).thenReturn(Map.of());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Node not found or not skippable: " + nodeId))
        .verify();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    // When
    int actualPriority = processor.getPriority();

    // Then
    assertThat(actualPriority).isEqualTo(10);
  }
}
