// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.LinguisticNaming",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.AvoidDuplicateLiterals"
})
class StopWorkflowCommandProcessorTest {

  private static final String EXEC = "exec-1";

  private final ExecutionControlStore registry = mock(ExecutionControlStore.class);
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);

  private StopWorkflowCommandProcessor processor;
  private Sinks.One<Void> safeStop;
  private Sinks.One<Void> triggerSafeStop;
  private Sinks.One<Void> processorSafeStop;
  private ReactiveControlValve nodeValve;
  private ReactiveControlValve globalValve;
  private ExecutionControl control;

  @Test
  void drainTimeoutFromSettingsBuildsProcessorThatHandlesStops() {
    final StopWorkflowCommandProcessor fromSettings =
        new StopWorkflowCommandProcessor(
            registry, tracker, YuktaSettings.bind(Map.of("yukta.stop.drain-timeout", "300ms")));

    assertThat(fromSettings.canProcess(new StopWorkflowCommand(EXEC, "r"))).isTrue();
    assertThat(fromSettings.canProcess(new PauseWorkflowCommand(EXEC))).isFalse();
  }

  @BeforeEach
  void setUp() {
    processor = new StopWorkflowCommandProcessor(registry, tracker, Duration.ofMillis(300));
    safeStop = Sinks.one();
    triggerSafeStop = Sinks.one();
    processorSafeStop = Sinks.one();
    nodeValve = new ReactiveControlValve();
    globalValve = new ReactiveControlValve();

    final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
    final WorkflowNode trigger = new WorkflowNode("t", "trigger", Map.of());
    when(prepared.parentsList())
        .thenReturn(Map.of("t", List.of(), "p", List.of(trigger), "orphan", List.of()));

    control =
        new ExecutionControl(
            "s",
            "w",
            EXEC,
            prepared,
            Map.of(),
            safeStop,
            globalValve,
            Map.of(),
            Map.of("t", triggerSafeStop, "p", processorSafeStop),
            Map.of("p", nodeValve),
            Map.of(),
            new AtomicBoolean());
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.of(control));
  }

  private static boolean completed(final Sinks.One<Void> sink) {
    return sink.asMono().toFuture().isDone();
  }

  @Test
  void canProcess_stopWorkflowCommand_returnsTrue() {
    final ExecutionControlCommand command = new StopWorkflowCommand(EXEC, "User initiated stop");

    assertThat(processor.canProcess(command)).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    assertThat(processor.canProcess(new PauseWorkflowCommand(EXEC))).isFalse();
  }

  @Test
  void commandDefaultsToDrain() {
    assertThat(new StopWorkflowCommand(EXEC, "r").mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand(EXEC, "r", null).mode()).isEqualTo(StopMode.DRAIN);
  }

  @Test
  void drain_stopsOnlyTheTriggers_marksStopRequested_andReportsStopping() {
    StepVerifier.create(processor.process(new StopWorkflowCommand(EXEC, "r", StopMode.DRAIN)))
        .verifyComplete();

    assertThat(completed(triggerSafeStop)).isTrue();
    assertThat(completed(processorSafeStop)).isFalse();
    assertThat(completed(safeStop)).isFalse();
    assertThat(control.stopRequested()).isTrue();
    verify(tracker).emitControlStateEvent(EXEC, ControlState.STOPPING);
  }

  @Test
  void drain_resumesPausedValvesSoInFlightWorkCanFinish() {
    nodeValve.pause();
    globalValve.pause();

    StepVerifier.create(processor.process(new StopWorkflowCommand(EXEC, "r"))).verifyComplete();

    assertThat(nodeValve.isPaused()).isFalse();
    assertThat(globalValve.isPaused()).isFalse();
  }

  @Test
  void drain_withoutGlobalValve_stillStopsTheTriggers() {
    final ExecutionControl noGlobal =
        new ExecutionControl(
            "s",
            "w",
            "no-global",
            control.prepared(),
            Map.of(),
            safeStop,
            null,
            Map.of(),
            Map.of("t", triggerSafeStop),
            Map.of(),
            Map.of(),
            new AtomicBoolean());
    when(registry.findByExecutionId("no-global")).thenReturn(Optional.of(noGlobal));

    StepVerifier.create(processor.process(new StopWorkflowCommand("no-global", "r")))
        .verifyComplete();

    assertThat(completed(triggerSafeStop)).isTrue();
  }

  @Test
  void drain_cancelsAfterTheDeadlineIfTheRunIsStillGoing() {
    StepVerifier.create(processor.process(new StopWorkflowCommand(EXEC, "r"))).verifyComplete();
    assertThat(completed(safeStop)).isFalse();

    await().atMost(Duration.ofSeconds(5)).until(() -> completed(safeStop));
  }

  @Test
  void drain_withoutPreparedWorkflow_fallsBackToCancel() {
    final ExecutionControl bare =
        new ExecutionControl(
            "s",
            "w",
            "bare",
            null,
            Map.of(),
            safeStop,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            new AtomicBoolean());
    when(registry.findByExecutionId("bare")).thenReturn(Optional.of(bare));

    StepVerifier.create(processor.process(new StopWorkflowCommand("bare", "r"))).verifyComplete();

    assertThat(completed(safeStop)).isTrue();
    verify(tracker, never()).emitControlStateEvent("bare", ControlState.STOPPING);
  }

  @Test
  void cancel_cancelsAtOnce_andStoppingTwiceIsNoOp() {
    final StopWorkflowCommand cancel = new StopWorkflowCommand(EXEC, "r", StopMode.CANCEL);

    StepVerifier.create(processor.process(cancel)).verifyComplete();
    StepVerifier.create(processor.process(cancel)).verifyComplete();

    assertThat(completed(safeStop)).isTrue();
    assertThat(completed(triggerSafeStop)).isFalse();
    assertThat(control.stopRequested()).isTrue();
  }

  @Test
  void kill_returnsStopDirectiveSoTheExecutionIsRemoved() {
    StepVerifier.create(processor.process(new StopWorkflowCommand(EXEC, "boom", StopMode.KILL)))
        .assertNext(
            directive -> assertThat(directive).isEqualTo(new WorkflowDirective.Stop("boom")))
        .verifyComplete();

    assertThat(control.stopRequested()).isTrue();
  }

  @Test
  void unknownExecution_isRejected() {
    when(registry.findByExecutionId("nope")).thenReturn(Optional.empty());

    StepVerifier.create(processor.process(new StopWorkflowCommand("nope", "r")))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Execution not found: nope"))
        .verify();
  }

  @Test
  void getPriority_returnsCorrectValue() {
    assertThat(processor.getPriority()).isEqualTo(20);
  }
}
