// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports"
})
@NoArgsConstructor
class DirectiveDispatcherTest {

  private ExecutionControlRegistry registry;
  private ControlBusService controlBusService;
  private ControlSignalProcessor processor;
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
    controlBusService = mock(ControlBusService.class);
    processor = mock(ControlSignalProcessor.class);

    when(controlBusService.getControlStream()).thenReturn(Flux.never());

    dispatcher = new DirectiveDispatcher(List.of(processor), registry, controlBusService);
  }

  @Test
  void dispatch_commandWithActiveExecution_invokesProcessorAndAppliesDirective() {
    verifyDispatchWithActiveExecutionInvokesProcessorAndAppliesDirective();
  }

  private void verifyDispatchWithActiveExecutionInvokesProcessorAndAppliesDirective() {
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
    verifyDispatchWithNoActiveExecutionCompletesEmpty();
  }

  private void verifyDispatchWithNoActiveExecutionCompletesEmpty() {
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
    verifyDispatchWithProcessorNotFoundThrowsIllegalArgumentException();
  }

  private void verifyDispatchWithProcessorNotFoundThrowsIllegalArgumentException() {
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
    verifyDispatchWithMultipleProcessorsSelectsByHighestPriority();
  }

  private void verifyDispatchWithMultipleProcessorsSelectsByHighestPriority() {
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
        .thenReturn(Mono.just(new WorkflowDirective.Stop("test")));

    final DirectiveDispatcher multiDispatcher =
        new DirectiveDispatcher(
            List.of(lowPriorityProcessor, highPriorityProcessor), registry, controlBusService);

    final ExecutionControlCommand command =
        new ExecutionControlCommand.StopNodeCommand(executionId, "node-1", false, "test");

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
  void init_subscribesToControlStream_filtersExecutionControlCommands() {
    // Given - mock control stream with a mix of command and non-command messages
    final Message<ExecutionControlCommand> commandMessage = mock(Message.class);
    final Message<String> nonCommandMessage = mock(Message.class);

    when(commandMessage.getPayload())
        .thenReturn(new ExecutionControlCommand.RestartCommand("exec-1", "new-exec-1"));
    when(nonCommandMessage.getPayload()).thenReturn("not a command");

    when(controlBusService.getControlStream())
        .thenReturn(Flux.just(commandMessage, nonCommandMessage));

    when(processor.canProcess(any())).thenReturn(false);

    // When - initialize dispatcher (triggers @PostConstruct)
    final DirectiveDispatcher newDispatcher =
        new DirectiveDispatcher(List.of(processor), registry, controlBusService);

    newDispatcher.init();

    // Then - verify control stream was subscribed
    verify(controlBusService, times(1)).getControlStream();
  }

  @Test
  void init_dispatchErrorHandling_logsAndContinues() {
    // Given - mock control stream that will trigger dispatch error
    final ExecutionControlCommand command =
        new ExecutionControlCommand.RestartCommand("exec-no-exist", "new-exec-no-exist");
    final Message<ExecutionControlCommand> errorMessage = mock(Message.class);
    when(errorMessage.getPayload()).thenReturn(command);

    when(controlBusService.getControlStream()).thenReturn(Flux.just(errorMessage));

    when(processor.canProcess(command)).thenReturn(false);

    // When - initialize dispatcher with processor that throws
    final DirectiveDispatcher newDispatcher =
        new DirectiveDispatcher(List.of(processor), registry, controlBusService);

    // This should not throw; error should be handled with onErrorResume
    newDispatcher.init();

    // Then - verify control stream was subscribed and error was handled gracefully
    verify(controlBusService, times(1)).getControlStream();
  }

  // Legacy test names for backward compatibility
  @Test
  void testDispatchStopCommand() {
    verifyDispatchWithActiveExecutionInvokesProcessorAndAppliesDirective();
  }

  @Test
  void testDispatchNoActiveExecution() {
    verifyDispatchWithNoActiveExecutionCompletesEmpty();
  }

  @Test
  void testDispatchProcessorNotFound() {
    verifyDispatchWithProcessorNotFoundThrowsIllegalArgumentException();
  }

  @Test
  void testDispatchMultipleProcessorsPriority() {
    verifyDispatchWithMultipleProcessorsSelectsByHighestPriority();
  }
}
