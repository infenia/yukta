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
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentRequired",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports"
})
@NoArgsConstructor
class DirectiveDispatcherTest {

  private ExecutionControlRegistry registry;
  private ControlBusService controlBusService;
  private ControlSignalProcessor processor;
  private ExecutionArchive archive;

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
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
    archive = new ExecutionArchive();
    controlBusService = mock(ControlBusService.class);
    processor = mock(ControlSignalProcessor.class);

    when(controlBusService.getControlStream()).thenReturn(Flux.never());

    dispatcher =
        new DirectiveDispatcher(
            List.of(processor),
            registry,
            controlBusService,
            archive,
            log,
            new ControlReviewer(
                new StaticListableBeanFactory().getBeanProvider(ExecutionSupervisor.class)),
            new ExecutionEventFeed());
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
            List.of(lowPriorityProcessor, highPriorityProcessor),
            registry,
            controlBusService,
            archive,
            log,
            new ControlReviewer(
                new StaticListableBeanFactory().getBeanProvider(ExecutionSupervisor.class)),
            new ExecutionEventFeed());

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
  void dispatchOrdered_recordsAppliedAndRejectedCommandsOfKnownRuns() {
    final List<com.infenia.yukta.security.AuditRecord> sunk = new ArrayList<>();
    dispatcher.setAuditSink(sunk::add);
    registry.register(createControl("s", "w", "exec-audit"));
    final ExecutionControlCommand pause =
        new ExecutionControlCommand.PauseWorkflowCommand("exec-audit");
    final ExecutionControlCommand resume =
        new ExecutionControlCommand.ResumeWorkflowCommand("exec-audit");
    when(processor.canProcess(any())).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    when(processor.process(pause)).thenReturn(Mono.empty());
    when(processor.process(resume)).thenReturn(Mono.error(new IllegalStateException("not paused")));

    StepVerifier.create(dispatcher.dispatchOrdered(pause)).verifyComplete();
    StepVerifier.create(dispatcher.dispatchOrdered(resume)).expectError().verify();

    assertThat(log.readAll())
        .extracting(
            ExecutionLogEvent::type, ExecutionLogEvent::executionId, ExecutionLogEvent::status)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple(
                ExecutionLogEvent.Type.CONTROL_APPLIED, "exec-audit", "PauseWorkflowCommand"),
            org.assertj.core.api.Assertions.tuple(
                ExecutionLogEvent.Type.CONTROL_REJECTED,
                "exec-audit",
                "ResumeWorkflowCommand: not paused"));
    assertThat(sunk)
        .extracting(
            com.infenia.yukta.security.AuditRecord::applied,
            com.infenia.yukta.security.AuditRecord::command,
            com.infenia.yukta.security.AuditRecord::reason)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple(true, "PauseWorkflowCommand", null),
            org.assertj.core.api.Assertions.tuple(false, "ResumeWorkflowCommand", "not paused"));
  }

  @Test
  void dispatchOrdered_cancelledCommandIsRecordedAsRejected() {
    registry.register(createControl("s", "w", "exec-cancel"));
    final ExecutionControlCommand pause =
        new ExecutionControlCommand.PauseWorkflowCommand("exec-cancel");
    when(processor.canProcess(any())).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    when(processor.process(pause)).thenReturn(Mono.never());

    dispatcher.dispatchOrdered(pause).subscribe().dispose();

    assertThat(log.readAll())
        .extracting(ExecutionLogEvent::type, ExecutionLogEvent::status)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple(
                ExecutionLogEvent.Type.CONTROL_REJECTED, "PauseWorkflowCommand: cancelled"));
  }

  @Test
  void dispatchOrdered_unknownRunsAreNotRecorded() {
    StepVerifier.create(
            dispatcher.dispatchOrdered(new ExecutionControlCommand.PauseWorkflowCommand("ghost")))
        .expectError()
        .verify();

    assertThat(log.readAll()).isEmpty();
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

  @Test
  void dispatchOrdered_restartOfFinishedArchivedExecution_reachesTheProcessor() {
    archive.put(
        new com.infenia.yukta.service.archive.ArchivedExecution(
            "finished",
            "s",
            "w",
            mock(com.infenia.yukta.model.workflow.PreparedWorkflow.class),
            "v",
            Map.of()));
    final ExecutionControlCommand restart =
        new ExecutionControlCommand.RestartCommand("finished", "new-1");
    when(processor.canProcess(restart)).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    when(processor.process(restart)).thenReturn(Mono.empty());

    StepVerifier.create(dispatcher.dispatchOrdered(restart)).verifyComplete();

    verify(processor).process(restart);
  }

  @Test
  void dispatchOrdered_otherCommandsForFinishedExecution_stillNeedLiveExecution() {
    archive.put(
        new com.infenia.yukta.service.archive.ArchivedExecution(
            "finished",
            "s",
            "w",
            mock(com.infenia.yukta.model.workflow.PreparedWorkflow.class),
            "v",
            Map.of()));

    StepVerifier.create(
            dispatcher.dispatchOrdered(
                new ExecutionControlCommand.PauseWorkflowCommand("finished")))
        .expectErrorMatches(e -> e.getMessage().contains("Execution not found"))
        .verify();
  }

  @Test
  void dispatchOrdered_restartOfAnUnknownExecution_isRejected() {
    StepVerifier.create(
            dispatcher.dispatchOrdered(
                new ExecutionControlCommand.RestartFromNodeCommand("gone", "n", "new")))
        .expectErrorMatches(e -> e.getMessage().contains("Execution not found: gone"))
        .verify();
  }

  @Test
  void dispatchOrdered_cancelledWaiterOfRunThatIsGone_isNotRecorded() {
    registry.register(createControl("s", "w", "exec-gone"));
    when(processor.canProcess(any())).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    final ExecutionControlCommand running =
        new ExecutionControlCommand.PauseWorkflowCommand("exec-gone");
    final ExecutionControlCommand waiting =
        new ExecutionControlCommand.ResumeWorkflowCommand("exec-gone");
    when(processor.process(running)).thenReturn(Mono.never());
    final var first = dispatcher.dispatchOrdered(running).subscribe();
    final var second = dispatcher.dispatchOrdered(waiting).subscribe();
    registry.unregister("exec-gone");

    second.dispose();

    assertThat(log.readAll()).isEmpty();
    first.dispose();
  }

  @Test
  void dispatchOrdered_cancelledCommandNamesItsActor() {
    registry.register(createControl("s", "w", "exec-actor"));
    final ExecutionControlCommand pause =
        new ExecutionControlCommand.PauseWorkflowCommand("exec-actor");
    when(processor.canProcess(any())).thenReturn(true);
    when(processor.getPriority()).thenReturn(1);
    when(processor.process(pause)).thenReturn(Mono.never());

    dispatcher
        .dispatchOrdered(pause)
        .contextWrite(
            reactor.util.context.Context.of(
                com.infenia.yukta.plugin.control.ControlActor.CONTEXT_KEY, "key:bob"))
        .subscribe()
        .dispose();

    assertThat(log.readAll())
        .extracting(ExecutionLogEvent::status)
        .containsExactly("PauseWorkflowCommand [by key:bob]: cancelled");
  }
}
