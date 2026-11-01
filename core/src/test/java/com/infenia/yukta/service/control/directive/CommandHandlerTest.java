// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetLimitsCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetPriorityCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CommandHandlerTest {

  private static final String EXECUTION_ID = "exec-1";

  private final CommandHandlerFixture fixture = new CommandHandlerFixture();

  @Test
  void handle_routesCommandToItsProcessor() {
    final PauseWorkflowCommand command = new PauseWorkflowCommand(EXECUTION_ID);
    when(fixture.pauseWorkflow.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.pauseWorkflow).process(command);
  }

  @Test
  void handle_commandWithoutProcessor_isRejected() {
    final ExecutionControlCommand command = new SetLimitsCommand(1, 1);

    StepVerifier.create(fixture.handler.handle(command))
        .expectErrorMessage("No processor registered for command")
        .verify();
  }

  @Test
  void handle_resumeWorkflowCommand_routesToResumeWorkflowProcessor() {
    final ResumeWorkflowCommand command = new ResumeWorkflowCommand(EXECUTION_ID);
    when(fixture.resumeWorkflow.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.resumeWorkflow).process(command);
  }

  @Test
  void handle_stopWorkflowCommand_routesToStopWorkflowProcessor() {
    final StopWorkflowCommand command = new StopWorkflowCommand(EXECUTION_ID, "reason");
    when(fixture.stopWorkflow.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.stopWorkflow).process(command);
  }

  @Test
  void handle_restartCommand_routesToRestartProcessor() {
    final RestartCommand command = new RestartCommand(EXECUTION_ID, "exec-2");
    when(fixture.restart.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.restart).process(command);
  }

  @Test
  void handle_processorError_isPropagated() {
    final PauseWorkflowCommand command = new PauseWorkflowCommand(EXECUTION_ID);
    when(fixture.pauseWorkflow.process(any()))
        .thenReturn(Mono.error(new IllegalStateException("execution not running")));

    StepVerifier.create(fixture.handler.handle(command))
        .expectErrorMessage("execution not running")
        .verify();
  }

  @Test
  void handle_commandsWithoutProcessor_areAllRejected() {
    final ExecutionControlCommand[] commands = {
      new SetLimitsCommand(1, 1),
      new SetPriorityCommand(EXECUTION_ID, 5),
      new AnswerInputCommand(
          EXECUTION_ID, "request-1", null, "approve", null, Map.of(), null, null),
      new EscalateInputCommand(EXECUTION_ID, "request-1", "needs a second look", null)
    };

    for (final ExecutionControlCommand command : commands) {
      StepVerifier.create(fixture.handler.handle(command))
          .expectErrorMessage("No processor registered for command")
          .verify();
    }
  }
}
