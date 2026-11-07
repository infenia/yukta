// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static org.mockito.Mockito.mock;

import com.infenia.yukta.service.control.processor.AnswerInputCommandProcessor;
import com.infenia.yukta.service.control.processor.EscalateInputCommandProcessor;
import com.infenia.yukta.service.control.processor.NodeValveCommands;
import com.infenia.yukta.service.control.processor.PauseWorkflowCommandProcessor;
import com.infenia.yukta.service.control.processor.RestartCommandProcessor;
import com.infenia.yukta.service.control.processor.RestartFromNodeCommandProcessor;
import com.infenia.yukta.service.control.processor.ResumeWorkflowCommandProcessor;
import com.infenia.yukta.service.control.processor.SetBreakpointCommandProcessor;
import com.infenia.yukta.service.control.processor.SkipNodeCommandProcessor;
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
  /* default */ final NodeValveCommands nodeValves = mock(NodeValveCommands.class);
  /* default */ final StopNodeCommandProcessor stopNode = mock(StopNodeCommandProcessor.class);
  /* default */ final SkipNodeCommandProcessor skipNode = mock(SkipNodeCommandProcessor.class);
  /* default */ final SetBreakpointCommandProcessor setBreakpoint =
      mock(SetBreakpointCommandProcessor.class);
  /* default */ final AnswerInputCommandProcessor answerInput =
      mock(AnswerInputCommandProcessor.class);
  /* default */ final EscalateInputCommandProcessor escalateInput =
      mock(EscalateInputCommandProcessor.class);

  /* default */ final CommandHandler handler =
      new CommandHandler(
          pauseWorkflow,
          resumeWorkflow,
          stopWorkflow,
          restart,
          restartFromNode,
          nodeValves,
          stopNode,
          skipNode,
          setBreakpoint,
          answerInput,
          escalateInput);
}
