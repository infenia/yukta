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
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ResumeNodeCommandProcessorTest {

  @Mock private ExecutionControlRegistry registry;
  @Mock private DefaultTaskTrackerService taskTracker;
  @Mock private ExecutionControl executionControl;
  @Mock private ReactiveControlValve nodePauseValve;

  @InjectMocks private ResumeNodeCommandProcessor processor;

  @Test
  void canProcess_resumeNodeCommand_returnsTrue() {
    // Given
    ExecutionControlCommand command = new ResumeNodeCommand("exec-1", "node-1");

    // When
    boolean actualResult = processor.canProcess(command);

    // Then
    assertThat(actualResult).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    // Given
    ExecutionControlCommand command = new PauseNodeCommand("exec-1", "node-1");

    // When
    boolean actualResult = processor.canProcess(command);

    // Then
    assertThat(actualResult).isFalse();
  }

  @Test
  void process_nodeFound_resumesNodeValveAndEmitsEvent() {
    // Given
    String executionId = "exec-resume-node";
    String nodeId = "node-1";
    ResumeNodeCommand command = new ResumeNodeCommand(executionId, nodeId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodePauseValves()).thenReturn(Map.of(nodeId, nodePauseValve));

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result).verifyComplete();
    verify(nodePauseValve).resume();
    verify(taskTracker).emitWorkflowStatusEvent(executionId, "NODE_RESUMED");
  }

  @Test
  void process_executionNotFound_errorWithIllegalArgumentException() {
    // Given
    String executionId = "exec-not-found";
    ResumeNodeCommand command = new ResumeNodeCommand(executionId, "node-1");
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
    ResumeNodeCommand command = new ResumeNodeCommand(executionId, nodeId);
    when(registry.findByExecutionId(executionId)).thenReturn(Optional.of(executionControl));
    when(executionControl.nodePauseValves()).thenReturn(Map.of());

    // When
    var result = processor.process(command);

    // Then
    StepVerifier.create(result)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Node not found or not pausable: " + nodeId))
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
