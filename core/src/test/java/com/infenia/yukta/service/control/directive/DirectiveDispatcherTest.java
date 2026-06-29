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
package com.infenia.yukta.service.control.directive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@NoArgsConstructor
class DirectiveDispatcherTest {

  private ExecutionControlRegistry registry;
  private WorkflowOrchestrator orchestrator;
  private ControlBusService controlBusService;
  private ControlSignalProcessor processor;
  private NodeCheckpointStore checkpointStore;
  private DirectiveDispatcher dispatcher;

  private ExecutionControl createControl(
      final String sessionId, final String workflowId, final String executionId) {
    return new ExecutionControl(
        sessionId,
        workflowId,
        executionId,
        null,
        Map.of(),
        Sinks.one(),
        Sinks.one(),
        null,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }

  @BeforeEach
  void setUp() {
    registry = new ExecutionControlRegistry(new InMemoryExecutionControlStore());
    orchestrator = mock(WorkflowOrchestrator.class);
    controlBusService = mock(ControlBusService.class);
    processor = mock(ControlSignalProcessor.class);
    checkpointStore = new InMemoryNodeCheckpointStore();

    when(controlBusService.getControlStream()).thenReturn(Flux.never());
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
    when(orchestrator.restartFromNode(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Mono.empty());

    dispatcher =
        new DirectiveDispatcher(
            List.of(processor), registry, orchestrator, checkpointStore, controlBusService);
  }

  @Test
  void dispatch_commandWithActiveExecution_invokesProcessorAndAppliesDirective() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.StopNodeCommand(executionId, "node-1", false, "User requested");

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command))
        .thenReturn(Mono.just(new WorkflowDirective.Stop("User requested")));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    verify(processor, times(1)).process(command);
  }

  @Test
  void dispatch_noActiveExecution_completesEmpty() {
    final ExecutionControlCommand command =
        new ExecutionControlCommand.StopNodeCommand("exec-no-exist", "node-1", false, null);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    verify(processor, never()).process(command);
  }

  @Test
  void dispatch_processorNotFound_throwsIllegalArgumentException() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.StopNodeCommand(executionId, "node-1", false, null);

    when(processor.canProcess(command)).thenReturn(false);

    // When-Then
    StepVerifier.create(dispatcher.dispatch(command))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void dispatch_multipleProcessors_selectsByHighestPriority() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ControlSignalProcessor lowPriorityProcessor = mock(ControlSignalProcessor.class);
    final ControlSignalProcessor highPriorityProcessor = mock(ControlSignalProcessor.class);

    when(lowPriorityProcessor.canProcess(any())).thenReturn(true);
    when(lowPriorityProcessor.getPriority()).thenReturn(0);

    when(highPriorityProcessor.canProcess(any())).thenReturn(true);
    when(highPriorityProcessor.getPriority()).thenReturn(10);

    when(highPriorityProcessor.process(any()))
        .thenReturn(Mono.just(new WorkflowDirective.Restart()));

    final DirectiveDispatcher multiDispatcher =
        new DirectiveDispatcher(
            List.of(lowPriorityProcessor, highPriorityProcessor),
            registry,
            orchestrator,
            checkpointStore,
            controlBusService);

    final ExecutionControlCommand command = new ExecutionControlCommand.RestartCommand(executionId);

    // When
    StepVerifier.create(multiDispatcher.dispatch(command)).verifyComplete();

    // Then
    verify(highPriorityProcessor, times(1)).process(command);
    verify(lowPriorityProcessor, never()).process(command);
  }

  @Test
  void applyDirective_stopDirective_callsApplyStop() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.StopNodeCommand(executionId, "node-1", false, "Test stop");

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command)).thenReturn(Mono.just(new WorkflowDirective.Stop("Test stop")));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then - execution should be unregistered after stop
    assertThat(registry.findByExecutionId(executionId)).isEmpty();
  }

  @Test
  void applyDirective_restartDirective_callsApplyRestart() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command = new ExecutionControlCommand.RestartCommand(executionId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command)).thenReturn(Mono.just(new WorkflowDirective.Restart()));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then - execution should be unregistered after restart
    assertThat(registry.findByExecutionId(executionId)).isEmpty();
    verify(orchestrator, times(1))
        .execute(eq("session-1"), eq("workflow-1"), any(String.class), any(), anyMap());
  }

  @Test
  void applyDirective_restartFromNodeDirective_callsApplyRestartFromNode() {
    final String executionId = "exec-1";
    final String nodeId = "node-1";

    // Create prepared workflow with no parents for the target node
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of());

    final ExecutionControl control =
        new ExecutionControl(
            "session-1",
            "workflow-1",
            executionId,
            preparedWorkflow,
            Map.of(),
            Sinks.one(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());
    registry.register(control);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.RestartFromNodeCommand(executionId, nodeId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command))
        .thenReturn(Mono.just(new WorkflowDirective.RestartFromNode(nodeId)));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then - execution should be unregistered after restart
    assertThat(registry.findByExecutionId(executionId)).isEmpty();
  }

  @Test
  void applyStop_validExecution_unregistersAndEmitsEmpty() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.StopNodeCommand(executionId, "node-1", false, "User stop");

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command)).thenReturn(Mono.just(new WorkflowDirective.Stop("User stop")));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    // Verify execution was unregistered
    assertThat(registry.findByExecutionId(executionId)).isEmpty();
  }

  @Test
  void applyRestart_validExecution_unregistersAndStartsNewExecution() {
    final String executionId = "exec-1";
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";

    final ExecutionControl control = createControl(sessionId, workflowId, executionId);
    registry.register(control);

    final ExecutionControlCommand command = new ExecutionControlCommand.RestartCommand(executionId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command)).thenReturn(Mono.just(new WorkflowDirective.Restart()));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    // Verify old execution was unregistered
    assertThat(registry.findByExecutionId(executionId)).isEmpty();

    // Verify orchestrator.execute was called with new execution ID
    final var captor = ArgumentCaptor.forClass(String.class);
    verify(orchestrator, times(1))
        .execute(anyString(), anyString(), captor.capture(), any(), anyMap());

    final String newExecutionId = captor.getValue();
    assertThat(newExecutionId).isNotEqualTo(executionId);
    assertThat(newExecutionId).isNotEmpty();
  }

  @Test
  void applyRestart_orchestratorExecuteFails_logsError() {
    final String executionId = "exec-1";
    final Exception testError = new RuntimeException("Test error");

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command = new ExecutionControlCommand.RestartCommand(executionId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command)).thenReturn(Mono.just(new WorkflowDirective.Restart()));

    // Mock orchestrator to throw error on subscribe
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.error(testError));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then - verify error was handled and execution still unregistered
    assertThat(registry.findByExecutionId(executionId)).isEmpty();
  }

  @Test
  void applyRestartFromNode_withParentCheckpoints_loadsAndApplies() {
    final String executionId = "exec-1";
    final String nodeId = "node-2";
    final String parentNodeId = "node-1";

    // Create prepared workflow with parent relationship
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);
    final WorkflowNode parentNode = mock(WorkflowNode.class);
    when(parentNode.nodeId()).thenReturn(parentNodeId);

    when(preparedWorkflow.parentsList()).thenReturn(Map.of(nodeId, List.of(parentNode)));

    final ExecutionControl control =
        new ExecutionControl(
            "session-1",
            "workflow-1",
            executionId,
            preparedWorkflow,
            Map.of(),
            Sinks.one(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());
    registry.register(control);

    // Store a checkpoint for the parent node
    final Message<?> parentCheckpoint = mock(Message.class);
    checkpointStore.save(executionId, parentNodeId, parentCheckpoint).block();

    final ExecutionControlCommand command =
        new ExecutionControlCommand.RestartFromNodeCommand(executionId, nodeId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command))
        .thenReturn(Mono.just(new WorkflowDirective.RestartFromNode(nodeId)));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    // Verify execution was unregistered
    assertThat(registry.findByExecutionId(executionId)).isEmpty();

    // Verify orchestrator.restartFromNode was called with checkpoints
    final var checkpointsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator, times(1))
        .restartFromNode(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            checkpointsCaptor.capture());

    final Map<String, Message<?>> capturedCheckpoints = checkpointsCaptor.getValue();
    assertThat(capturedCheckpoints).containsKey(parentNodeId);
    assertThat(capturedCheckpoints.get(parentNodeId)).isEqualTo(parentCheckpoint);
  }

  @Test
  void applyRestartFromNode_parentCheckpointNotFound_continuesWithEmptyCheckpoints() {
    final String executionId = "exec-1";
    final String nodeId = "node-2";
    final String parentNodeId = "node-1";

    // Create prepared workflow with parent relationship but no checkpoint
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);
    final WorkflowNode parentNode = mock(WorkflowNode.class);
    when(parentNode.nodeId()).thenReturn(parentNodeId);

    when(preparedWorkflow.parentsList()).thenReturn(Map.of(nodeId, List.of(parentNode)));

    final ExecutionControl control =
        new ExecutionControl(
            "session-1",
            "workflow-1",
            executionId,
            preparedWorkflow,
            Map.of(),
            Sinks.one(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());
    registry.register(control);

    // Don't store any checkpoint for parent node

    final ExecutionControlCommand command =
        new ExecutionControlCommand.RestartFromNodeCommand(executionId, nodeId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command))
        .thenReturn(Mono.just(new WorkflowDirective.RestartFromNode(nodeId)));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    // Verify execution was still unregistered despite missing checkpoint
    assertThat(registry.findByExecutionId(executionId)).isEmpty();

    // Verify orchestrator.restartFromNode was called with empty checkpoints
    final var checkpointsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator, times(1))
        .restartFromNode(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            checkpointsCaptor.capture());

    final Map<String, Message<?>> capturedCheckpoints = checkpointsCaptor.getValue();
    assertThat(capturedCheckpoints).isEmpty();
  }

  @Test
  void applyRestartFromNode_noParents_executeWithEmptyCheckpointMap() {
    final String executionId = "exec-1";
    final String nodeId = "node-1";

    // Create prepared workflow with no parents for the node
    final PreparedWorkflow preparedWorkflow = mock(PreparedWorkflow.class);
    when(preparedWorkflow.parentsList()).thenReturn(Map.of());

    final ExecutionControl control =
        new ExecutionControl(
            "session-1",
            "workflow-1",
            executionId,
            preparedWorkflow,
            Map.of(),
            Sinks.one(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());
    registry.register(control);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.RestartFromNodeCommand(executionId, nodeId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command))
        .thenReturn(Mono.just(new WorkflowDirective.RestartFromNode(nodeId)));

    // When
    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    // Then
    // Verify execution was unregistered
    assertThat(registry.findByExecutionId(executionId)).isEmpty();

    // Verify orchestrator.restartFromNode was called with empty checkpoints
    final var checkpointsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator, times(1))
        .restartFromNode(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            checkpointsCaptor.capture());

    final Map<String, Message<?>> capturedCheckpoints = checkpointsCaptor.getValue();
    assertThat(capturedCheckpoints).isEmpty();
  }

  @Test
  void init_subscribesToControlStream_filtersExecutionControlCommands() {
    // Given - mock control stream with a mix of command and non-command messages
    final Message<ExecutionControlCommand> commandMessage = mock(Message.class);
    final Message<String> nonCommandMessage = mock(Message.class);

    when(commandMessage.getPayload())
        .thenReturn(new ExecutionControlCommand.RestartCommand("exec-1"));
    when(nonCommandMessage.getPayload()).thenReturn("not a command");

    when(controlBusService.getControlStream())
        .thenReturn(Flux.just(commandMessage, nonCommandMessage));

    when(processor.canProcess(any())).thenReturn(false);

    // When - initialize dispatcher (triggers @PostConstruct)
    final DirectiveDispatcher newDispatcher =
        new DirectiveDispatcher(
            List.of(processor), registry, orchestrator, checkpointStore, controlBusService);

    newDispatcher.init();

    // Then - verify control stream was subscribed
    verify(controlBusService, times(1)).getControlStream();
  }

  @Test
  void init_dispatchErrorHandling_logsAndContinues() {
    // Given - mock control stream that will trigger dispatch error
    final ExecutionControlCommand command =
        new ExecutionControlCommand.RestartCommand("exec-no-exist");
    final Message<ExecutionControlCommand> errorMessage = mock(Message.class);
    when(errorMessage.getPayload()).thenReturn(command);

    when(controlBusService.getControlStream()).thenReturn(Flux.just(errorMessage));

    when(processor.canProcess(command)).thenReturn(false);

    // When - initialize dispatcher with processor that throws
    final DirectiveDispatcher newDispatcher =
        new DirectiveDispatcher(
            List.of(processor), registry, orchestrator, checkpointStore, controlBusService);

    // This should not throw; error should be handled with onErrorResume
    newDispatcher.init();

    // Then - verify control stream was subscribed and error was handled gracefully
    verify(controlBusService, times(1)).getControlStream();
  }

  // Legacy test names for backward compatibility
  @Test
  void testDispatchStopCommand() {
    dispatch_commandWithActiveExecution_invokesProcessorAndAppliesDirective();
  }

  @Test
  void testDispatchNoActiveExecution() {
    dispatch_noActiveExecution_completesEmpty();
  }

  @Test
  void testDispatchRestartCommand() {
    final String executionId = "exec-1";

    final ExecutionControl control = createControl("session-1", "workflow-1", executionId);
    registry.register(control);

    final ExecutionControlCommand command = new ExecutionControlCommand.RestartCommand(executionId);

    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(0);
    when(processor.process(command)).thenReturn(Mono.just(new WorkflowDirective.Restart()));

    StepVerifier.create(dispatcher.dispatch(command)).verifyComplete();

    verify(processor, times(1)).process(command);
  }

  @Test
  void testDispatchProcessorNotFound() {
    dispatch_processorNotFound_throwsIllegalArgumentException();
  }

  @Test
  void testDispatchMultipleProcessorsPriority() {
    dispatch_multipleProcessors_selectsByHighestPriority();
  }
}
