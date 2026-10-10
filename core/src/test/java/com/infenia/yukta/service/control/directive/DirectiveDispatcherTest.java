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

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
        null,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        new AtomicBoolean());
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
  void init_registersItselfAsTheCommandExecutor() {
    dispatcher.init();

    verify(controlBusService).setCommandExecutor(any());
  }

  @Test
  void dispatchOrdered_unknownExecution_isRejectedWithReason() {
    final ExecutionControlCommand command =
        new ExecutionControlCommand.PauseWorkflowCommand("nope");

    StepVerifier.create(dispatcher.dispatchOrdered(command))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Execution not found: nope"))
        .verify();
  }

  @Test
  void dispatchOrdered_completesOnlyAfterTheProcessorHasApplied_andPropagatesRejections() {
    registry.register(createControl("s", "w", "exec-ack"));
    final ExecutionControlCommand command =
        new ExecutionControlCommand.PauseWorkflowCommand("exec-ack");
    when(processor.canProcess(command)).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    final AtomicBoolean applied = new AtomicBoolean();
    when(processor.process(command))
        .thenReturn(
            Mono.fromRunnable(() -> applied.set(true))
                .then(Mono.error(new IllegalArgumentException("not pausable"))));

    StepVerifier.create(dispatcher.dispatchOrdered(command))
        .expectErrorMatches(e -> "not pausable".equals(e.getMessage()))
        .verify();
    assertThat(applied).isTrue();
  }

  @Test
  void dispatchOrdered_appliesCommandsOfOneExecutionInSubmissionOrder() {
    registry.register(createControl("s", "w", "exec-order"));
    final ExecutionControlCommand first =
        new ExecutionControlCommand.PauseWorkflowCommand("exec-order");
    final ExecutionControlCommand second =
        new ExecutionControlCommand.ResumeWorkflowCommand("exec-order");
    final List<String> applied = new java.util.concurrent.CopyOnWriteArrayList<>();
    when(processor.canProcess(any())).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    // the first command is slow; the second must still be applied after it
    when(processor.process(first))
        .thenReturn(
            Mono.delay(java.time.Duration.ofMillis(150))
                .doOnNext(_ -> applied.add("pause"))
                .then(Mono.empty()));
    when(processor.process(second))
        .thenReturn(Mono.fromRunnable(() -> applied.add("resume")).then(Mono.empty()));

    final Mono<Void> firstAck = dispatcher.dispatchOrdered(first);
    final Mono<Void> secondAck = dispatcher.dispatchOrdered(second);
    StepVerifier.create(Mono.zip(firstAck.materialize(), secondAck.materialize()).then())
        .verifyComplete();

    assertThat(applied).containsExactly("pause", "resume");
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
