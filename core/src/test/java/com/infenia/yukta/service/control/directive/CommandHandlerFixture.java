// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.mockito.Mockito.mock;

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

final class CommandHandlerFixture {
  /* default */ final PauseWorkflowCommandProcessor pauseWorkflow =
      mock(PauseWorkflowCommandProcessor.class);

  /* default */ final ResumeWorkflowCommandProcessor resumeWorkflow =
      mock(ResumeWorkflowCommandProcessor.class);
  /* default */ final StopWorkflowCommandProcessor stopWorkflow =
      mock(StopWorkflowCommandProcessor.class);
  /* default */ final RestartCommandProcessor restart = mock(RestartCommandProcessor.class);
  /* default */ final RestartFromNodeCommandProcessor restartFromNode =
      mock(RestartFromNodeCommandProcessor.class);
  /* default */ final PauseNodeCommandProcessor pauseNode = mock(PauseNodeCommandProcessor.class);
  /* default */ final ResumeNodeCommandProcessor resumeNode =
      mock(ResumeNodeCommandProcessor.class);
  /* default */ final StopNodeCommandProcessor stopNode = mock(StopNodeCommandProcessor.class);
  /* default */ final SkipNodeCommandProcessor skipNode = mock(SkipNodeCommandProcessor.class);
  /* default */ final EnableStepModeCommandProcessor enableStepMode =
      mock(EnableStepModeCommandProcessor.class);
  /* default */ final DisableStepModeCommandProcessor disableStepMode =
      mock(DisableStepModeCommandProcessor.class);
  /* default */ final StepNodeCommandProcessor stepNode = mock(StepNodeCommandProcessor.class);
  /* default */ final SetBreakpointCommandProcessor setBreakpoint =
      mock(SetBreakpointCommandProcessor.class);
  /* default */ final SignalNodeCommandProcessor signalNode =
      mock(SignalNodeCommandProcessor.class);

  /* default */ final CommandHandler handler =
      new CommandHandler(
          pauseWorkflow,
          resumeWorkflow,
          stopWorkflow,
          restart,
          restartFromNode,
          pauseNode,
          resumeNode,
          stopNode,
          skipNode,
          enableStepMode,
          disableStepMode,
          stepNode,
          setBreakpoint,
          signalNode);
}
