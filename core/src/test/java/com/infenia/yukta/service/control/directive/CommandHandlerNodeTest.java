// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetBreakpointCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CommandHandlerNodeTest {

  private static final String EXECUTION_ID = "exec-1";
  private static final String NODE_ID = "node-1";

  private final CommandHandlerFixture fixture = new CommandHandlerFixture();

  @Test
  void handle_restartFromNodeCommand_routesToRestartFromNodeProcessor() {
    final RestartFromNodeCommand command =
        new RestartFromNodeCommand(EXECUTION_ID, NODE_ID, "exec-2");
    when(fixture.restartFromNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.restartFromNode).process(command);
  }

  @Test
  void handle_pauseNodeCommand_routesToPauseNodeProcessor() {
    final PauseNodeCommand command = new PauseNodeCommand(EXECUTION_ID, NODE_ID);
    when(fixture.pauseNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.pauseNode).process(command);
  }

  @Test
  void handle_resumeNodeCommand_routesToResumeNodeProcessor() {
    final ResumeNodeCommand command = new ResumeNodeCommand(EXECUTION_ID, NODE_ID);
    when(fixture.resumeNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.resumeNode).process(command);
  }

  @Test
  void handle_stopNodeCommand_routesToStopNodeProcessor() {
    final StopNodeCommand command = new StopNodeCommand(EXECUTION_ID, NODE_ID, false, "reason");
    when(fixture.stopNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.stopNode).process(command);
  }

  @Test
  void handle_skipNodeCommand_routesToSkipNodeProcessor() {
    final SkipNodeCommand command = new SkipNodeCommand(EXECUTION_ID, NODE_ID, true);
    when(fixture.skipNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.skipNode).process(command);
  }

  @Test
  void handle_enableStepModeCommand_routesToEnableStepModeProcessor() {
    final EnableStepModeCommand command = new EnableStepModeCommand(EXECUTION_ID, NODE_ID);
    when(fixture.enableStepMode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.enableStepMode).process(command);
  }

  @Test
  void handle_disableStepModeCommand_routesToDisableStepModeProcessor() {
    final DisableStepModeCommand command = new DisableStepModeCommand(EXECUTION_ID, NODE_ID);
    when(fixture.disableStepMode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.disableStepMode).process(command);
  }

  @Test
  void handle_stepNodeCommand_routesToStepNodeProcessor() {
    final StepNodeCommand command = new StepNodeCommand(EXECUTION_ID, NODE_ID);
    when(fixture.stepNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.stepNode).process(command);
  }

  @Test
  void handle_setBreakpointCommand_routesToSetBreakpointProcessor() {
    final SetBreakpointCommand command =
        new SetBreakpointCommand(EXECUTION_ID, NODE_ID, "payload.status", "failed");
    when(fixture.setBreakpoint.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.setBreakpoint).process(command);
  }

  @Test
  void handle_signalNodeCommand_routesToSignalNodeProcessor() {
    final SignalNodeCommand command =
        new SignalNodeCommand(EXECUTION_ID, NODE_ID, "approval", "approve", null);
    when(fixture.signalNode.process(any())).thenReturn(Mono.empty());

    StepVerifier.create(fixture.handler.handle(command)).verifyComplete();

    verify(fixture.signalNode).process(command);
  }
}
