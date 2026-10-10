// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.service.archive.ArchivedExecution;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.ExecutionRejectedException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.LinguisticNaming",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods"
})
class RestartCommandProcessorTest {

  private final ExecutionControlRegistry registry = mock(ExecutionControlRegistry.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final RestartCompletionSink completionSink = mock(RestartCompletionSink.class);
  private final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
  private final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
  private final ExecutionArchive archive = new ExecutionArchive();

  private RestartCommandProcessor processor;

  @BeforeEach
  void setUp() {
    processor =
        new RestartCommandProcessor(registry, archive, orchestrator, scheduler, completionSink);
    archive.put(new ArchivedExecution("exec-1", "s1", "w1", prepared, "v1", Map.of("k", "v")));
  }

  @Test
  void canProcess_restartCommand_returnsTrue() {
    final ExecutionControlCommand command = new RestartCommand("exec-1", "new-1");

    assertThat(processor.canProcess(command)).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    assertThat(processor.canProcess(new PauseWorkflowCommand("exec-1"))).isFalse();
  }

  @Test
  void finishedExecution_isRestartedWithItsOriginalGraphAndPayload() {
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.empty());
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.never());

    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-1"))).verifyComplete();

    verify(orchestrator).execute("s1", "w1", "new-1", prepared, Map.of("k", "v"));
    verify(registry, never()).unregister(any());
    verify(completionSink).completeRestartSuccess("new-1");
  }

  @Test
  void runningExecution_isStoppedAndDetachedFirst() {
    final ExecutionControl control = mock(ExecutionControl.class);
    final Sinks.One<Void> safeStop = Sinks.one();
    when(control.executionId()).thenReturn("exec-1");
    when(control.safeStopSink()).thenReturn(safeStop);
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.of(control));
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.never());

    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-1"))).verifyComplete();

    assertThat(safeStop.asMono().toFuture()).isDone();
    verify(registry).unregister("exec-1");
    verify(completionSink).completeRestartSuccess("new-1");
  }

  @Test
  void finishedExecution_isRefusedWhenTheLimitsAreFull() {
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new ExecutionRejectedException("Execution limit reached"))
        .when(scheduler)
        .assertCanAccept("s1", "w1");

    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-1"))).verifyComplete();

    verify(completionSink)
        .completeRestartFailure(eq("new-1"), any(ExecutionRejectedException.class));
    verify(orchestrator, never()).execute(any(), any(), any(), any(), any());
  }

  @Test
  void runningExecution_isNotCheckedAgainstTheLimits() {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.executionId()).thenReturn("exec-1");
    when(control.safeStopSink()).thenReturn(Sinks.one());
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.of(control));
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.never());

    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-1"))).verifyComplete();

    verify(scheduler, never()).assertCanAccept(any(), any());
  }

  @Test
  void unknownExecution_reportsFailure_andStartsNothing() {
    StepVerifier.create(processor.process(new RestartCommand("gone", "new-2"))).verifyComplete();

    verify(completionSink).completeRestartFailure(eq("new-2"), any(IllegalArgumentException.class));
    verify(orchestrator, never()).execute(any(), any(), any(), any(), any());
  }

  @Test
  void orchestratorFailingLater_isNotReportedThroughTheCompletionSink() {
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.empty());
    when(orchestrator.execute(any(), any(), any(), any(), any()))
        .thenReturn(Mono.error(new RuntimeException("Orchestrator failure")));

    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-1"))).verifyComplete();

    verify(completionSink).completeRestartSuccess("new-1");
    verify(completionSink, never()).completeRestartFailure(any(), any());
  }

  @Test
  void restartingTwiceInSequence_succeedsBothTimes() {
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.empty());
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.never());

    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-1"))).verifyComplete();
    StepVerifier.create(processor.process(new RestartCommand("exec-1", "new-2"))).verifyComplete();

    verify(completionSink).completeRestartSuccess("new-1");
    verify(completionSink).completeRestartSuccess("new-2");
  }

  @Test
  void secondRestartWhileOneIsInProgress_isRejected() {
    when(registry.findByExecutionId("exec-1")).thenReturn(Optional.empty());
    when(orchestrator.execute(any(), any(), any(), any(), any())).thenReturn(Mono.never());

    final var first = processor.process(new RestartCommand("exec-1", "new-1"));
    final var second = processor.process(new RestartCommand("exec-1", "new-2"));

    StepVerifier.create(second).verifyComplete();
    verify(completionSink).completeRestartFailure(eq("new-2"), any(IllegalStateException.class));
    StepVerifier.create(first).verifyComplete();
    verify(completionSink).completeRestartSuccess("new-1");
  }

  @Test
  void getPriority_returnsCorrectValue() {
    assertThat(processor.getPriority()).isEqualTo(20);
  }
}
