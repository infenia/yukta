// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnnecessaryFullyQualifiedName", "PMD.UnusedLocalVariable"})
class ExecutionControlCommandTest {

  @Test
  void stopWorkflowDefaultsToDrain() {
    assertThat(new StopWorkflowCommand("e", "r").mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand("e", "r", null).mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand("e", "r", StopMode.KILL).mode()).isEqualTo(StopMode.KILL);
  }

  @Test
  void schedulerCommandsBelongToNoRun() {
    assertThat(new ExecutionControlCommand.SetLimitsCommand(2, 1).executionId())
        .isEqualTo(ExecutionControlCommand.SCHEDULER);
    assertThat(new ExecutionControlCommand.SetPriorityCommand("e", 3).executionId()).isEqualTo("e");
  }

  @Test
  void commandsCanBeSwitchedOverExhaustively() {
    final ExecutionControlCommand command = new ExecutionControlCommand.PauseNodeCommand("e", "n");

    final String kind =
        switch (command) {
          case ExecutionControlCommand.PauseWorkflowCommand c -> "pause-workflow";
          case ExecutionControlCommand.ResumeWorkflowCommand c -> "resume-workflow";
          case ExecutionControlCommand.PauseNodeCommand c -> "pause-node";
          case ExecutionControlCommand.ResumeNodeCommand c -> "resume-node";
          case ExecutionControlCommand.StopNodeCommand c -> "stop-node";
          case ExecutionControlCommand.SkipNodeCommand c -> "skip-node";
          case ExecutionControlCommand.EnableStepModeCommand c -> "enable-step";
          case ExecutionControlCommand.DisableStepModeCommand c -> "disable-step";
          case ExecutionControlCommand.StepNodeCommand c -> "step";
          case StopWorkflowCommand c -> "stop-workflow";
          case ExecutionControlCommand.RestartCommand c -> "restart";
          case ExecutionControlCommand.RestartFromNodeCommand c -> "restart-from-node";
          case ExecutionControlCommand.SetBreakpointCommand c -> "set-breakpoint";
          case ExecutionControlCommand.SignalNodeCommand _ -> "signal";
          case ExecutionControlCommand.SetLimitsCommand c -> "limits-" + c.maxConcurrent();
          case ExecutionControlCommand.SetPriorityCommand c -> "priority-" + c.priority();
        };

    assertThat(kind).isEqualTo("pause-node");
  }

  @Test
  void everyCommandCarriesItsExecutionId() {
    final java.util.List<ExecutionControlCommand> all =
        java.util.List.of(
            new ExecutionControlCommand.PauseWorkflowCommand("e"),
            new ExecutionControlCommand.ResumeWorkflowCommand("e"),
            new ExecutionControlCommand.PauseNodeCommand("e", "n"),
            new ExecutionControlCommand.ResumeNodeCommand("e", "n"),
            new ExecutionControlCommand.StopNodeCommand("e", "n", true, null),
            new ExecutionControlCommand.SkipNodeCommand("e", "n", true),
            new ExecutionControlCommand.EnableStepModeCommand("e", "n"),
            new ExecutionControlCommand.DisableStepModeCommand("e", "n"),
            new ExecutionControlCommand.StepNodeCommand("e", "n"),
            new StopWorkflowCommand("e", null),
            new ExecutionControlCommand.RestartCommand("e", "e2"),
            new ExecutionControlCommand.RestartFromNodeCommand("e", "n", "e2"),
            new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload.x", "1"),
            new ExecutionControlCommand.SignalNodeCommand("e", "n", "approval", "approve", null));

    assertThat(all).extracting(ExecutionControlCommand::executionId).containsOnly("e");
  }

  @Test
  void signalNeedsNameAndDecision() {
    assertThat(new ExecutionControlCommand.SignalNodeCommand("e", "n", "ok", "approve", "x").data())
        .isEqualTo("x");
    assertThatThrownBy(
            () -> new ExecutionControlCommand.SignalNodeCommand("e", "n", " ", "a", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ExecutionControlCommand.SignalNodeCommand("e", "n", "ok", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ExecutionControlCommand.SignalNodeCommand("e", "n", null, "a", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ExecutionControlCommand.SignalNodeCommand("e", "n", "ok", " ", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void processorPriorityDefaultsToZero() {
    final ControlSignalProcessor processor =
        new ControlSignalProcessor() {
          @Override
          public boolean canProcess(final ExecutionControlCommand command) {
            return true;
          }

          @Override
          public reactor.core.publisher.Mono<WorkflowDirective> process(
              final ExecutionControlCommand command) {
            return reactor.core.publisher.Mono.just(new WorkflowDirective.Stop("done"));
          }
        };

    assertThat(processor.getPriority()).isZero();
    assertThat(processor.process(new StopWorkflowCommand("e", null)).block())
        .isEqualTo(new WorkflowDirective.Stop("done"));
  }

  @Test
  void setBreakpoint_acceptsPayloadAndMetadataFields_andRejectsOthers() {
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload", "x").field())
        .isEqualTo("payload");
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload.a.b", "x"))
        .isNotNull();
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", "metadata.k", "x"))
        .isNotNull();
    assertThat(new ExecutionControlCommand.SetBreakpointCommand("e", "n", null, null).field())
        .isNull();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payloadX", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Breakpoint field");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ExecutionControlCommand.SetBreakpointCommand("e", "n", "payload", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value to compare");
  }
}
