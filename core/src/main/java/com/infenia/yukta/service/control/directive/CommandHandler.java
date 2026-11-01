// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.control.processor.DisableStepModeCommandProcessor;
import com.infenia.yukta.service.control.processor.EnableStepModeCommandProcessor;
import com.infenia.yukta.service.control.processor.PauseNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.PauseWorkflowCommandProcessor;
import com.infenia.yukta.service.control.processor.RestartCommandProcessor;
import com.infenia.yukta.service.control.processor.RestartFromNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.ResumeNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.ResumeWorkflowCommandProcessor;
import com.infenia.yukta.service.control.processor.SetBreakpointCommandProcessor;
import com.infenia.yukta.service.control.processor.SignalNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.SkipNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.StepNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.StopNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.StopWorkflowCommandProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Runs an {@link ExecutionControlCommand} by handing it to the class that implements it. The {@code
 * switch} is exhaustive over the sealed command type, so the compiler reports a command that has no
 * handler.
 */
@Component
@RequiredArgsConstructor
public class CommandHandler {

  private final PauseWorkflowCommandProcessor pauseWorkflow;
  private final ResumeWorkflowCommandProcessor resumeWorkflow;
  private final StopWorkflowCommandProcessor stopWorkflow;
  private final RestartCommandProcessor restart;
  private final RestartFromNodeCommandProcessor restartFromNode;
  private final PauseNodeCommandProcessor pauseNode;
  private final ResumeNodeCommandProcessor resumeNode;
  private final StopNodeCommandProcessor stopNode;
  private final SkipNodeCommandProcessor skipNode;
  private final EnableStepModeCommandProcessor enableStepMode;
  private final DisableStepModeCommandProcessor disableStepMode;
  private final StepNodeCommandProcessor stepNode;
  private final SetBreakpointCommandProcessor setBreakpoint;
  private final SignalNodeCommandProcessor signalNode;

  /**
   * Runs one command.
   *
   * @param command the command
   * @return completes when it has been applied; errors when it is rejected or has no handler here
   */
  public Mono<Void> handle(final ExecutionControlCommand command) {
    return switch (command) {
      case ExecutionControlCommand.PauseWorkflowCommand c -> pauseWorkflow.process(c);
      case ExecutionControlCommand.ResumeWorkflowCommand c -> resumeWorkflow.process(c);
      case ExecutionControlCommand.StopWorkflowCommand c -> stopWorkflow.process(c);
      case ExecutionControlCommand.RestartCommand c -> restart.process(c);
      case ExecutionControlCommand.RestartFromNodeCommand c -> restartFromNode.process(c);
      case ExecutionControlCommand.PauseNodeCommand c -> pauseNode.process(c);
      case ExecutionControlCommand.ResumeNodeCommand c -> resumeNode.process(c);
      case ExecutionControlCommand.StopNodeCommand c -> stopNode.process(c);
      case ExecutionControlCommand.SkipNodeCommand c -> skipNode.process(c);
      case ExecutionControlCommand.EnableStepModeCommand c -> enableStepMode.process(c);
      case ExecutionControlCommand.DisableStepModeCommand c -> disableStepMode.process(c);
      case ExecutionControlCommand.StepNodeCommand c -> stepNode.process(c);
      case ExecutionControlCommand.SetBreakpointCommand c -> setBreakpoint.process(c);
      case ExecutionControlCommand.SignalNodeCommand c -> signalNode.process(c);
      // Reviewed by supervisors like any command, but nothing runs for them here.
      case ExecutionControlCommand.SetLimitsCommand _,
          ExecutionControlCommand.SetPriorityCommand _,
          ExecutionControlCommand.AnswerInputCommand _,
          ExecutionControlCommand.EscalateInputCommand _ ->
          Mono.error(new IllegalArgumentException("No processor registered for command"));
    };
  }
}
