// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import org.junit.jupiter.api.Test;

class ExecutionControlCommandTest {

  @Test
  void stopWorkflowDefaultsToDrain() {
    assertThat(new StopWorkflowCommand("e", "r").mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand("e", "r", null).mode()).isEqualTo(StopMode.DRAIN);
    assertThat(new StopWorkflowCommand("e", "r", StopMode.KILL).mode()).isEqualTo(StopMode.KILL);
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
            new ExecutionControlCommand.RestartFromNodeCommand("e", "n", "e2"));

    assertThat(all).extracting(ExecutionControlCommand::executionId).containsOnly("e");
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
}
