// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetLimitsCommand;
import com.infenia.yukta.service.control.processor.PauseWorkflowCommandProcessor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CommandHandlerTest {

  private final PauseWorkflowCommandProcessor pauseWorkflow =
      mock(PauseWorkflowCommandProcessor.class);

  private final CommandHandler handler =
      new CommandHandler(
          pauseWorkflow,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);

  @Test
  void handle_routesCommandToItsProcessor() {
    final PauseWorkflowCommand command = new PauseWorkflowCommand("exec-1");
    when(pauseWorkflow.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(handler.handle(command)).verifyComplete();

    verify(pauseWorkflow).process(command);
  }

  @Test
  void handle_commandWithoutProcessor_isRejected() {
    final ExecutionControlCommand command = new SetLimitsCommand(1, 1);

    StepVerifier.create(handler.handle(command))
        .expectErrorMessage("No processor registered for command")
        .verify();
  }
}
