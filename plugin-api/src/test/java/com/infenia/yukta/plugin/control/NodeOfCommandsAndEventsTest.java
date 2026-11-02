// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static com.infenia.yukta.plugin.input.InputFixtures.NOW;
import static com.infenia.yukta.plugin.input.InputFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetBreakpointCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetLimitsCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetPriorityCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.input.Responder;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests that commands and events name the node they are about, for section scopes. */
@NoArgsConstructor
class NodeOfCommandsAndEventsTest {

  @Test
  void nodeCommandsNameTheirNode() {
    final List<ExecutionControlCommand> commands =
        List.of(
            new PauseNodeCommand("e", "n"),
            new ResumeNodeCommand("e", "n"),
            new StopNodeCommand("e", "n", false, null),
            new SkipNodeCommand("e", "n", true),
            new EnableStepModeCommand("e", "n"),
            new DisableStepModeCommand("e", "n"),
            new StepNodeCommand("e", "n"),
            new SetBreakpointCommand("e", "n", null, null),
            new SignalNodeCommand("e", "n", "approval", "approve", null));

    assertThat(commands).extracting(ExecutionControlCommand::nodeId).containsOnly("n");
  }

  @Test
  void runAndSchedulerCommandsNameNoNode() {
    final List<ExecutionControlCommand> commands =
        List.of(
            new PauseWorkflowCommand("e"),
            new ResumeWorkflowCommand("e"),
            new StopWorkflowCommand("e", null),
            new RestartCommand("e", "e2"),
            new RestartFromNodeCommand("e", "n", "e2"),
            new SetLimitsCommand(1, 1),
            new SetPriorityCommand("e", 1),
            new AnswerInputCommand("e", "r", null, "ok", null, Map.of(), null, null),
            new EscalateInputCommand("e", "r", "why", null));

    assertThat(commands).extracting(ExecutionControlCommand::nodeId).containsOnlyNulls();
  }

  @Test
  void aRequestTakesTheNodeOfItsCommandUnlessGiven() {
    final ControlRequest node = new ControlRequest(new PauseNodeCommand("e", "n"), null, "s", "w");
    final ControlRequest run = new ControlRequest(new PauseWorkflowCommand("e"), null, "s", "w");
    final ControlRequest answer =
        new ControlRequest(new EscalateInputCommand("e", "r", "why", null), "a", "s", "w", "asker");

    assertThat(node.nodeId()).isEqualTo("n");
    assertThat(run.nodeId()).isNull();
    assertThat(answer.nodeId()).isEqualTo("asker");
  }

  @Test
  void eventsNameTheirNodeWhenTheyHaveOne() {
    final Responder responder = Responder.SYSTEM;

    assertThat(
            new ExecutionEvent.NodeStatusChanged("e", "s", "w", "n", "m", "RUNNING", null, NOW)
                .nodeId())
        .isEqualTo("n");
    assertThat(new ExecutionEvent.LogLine("e", "s", "w", "n", "x", LogLevel.INFO, NOW).nodeId())
        .isEqualTo("n");
    assertThat(new ExecutionEvent.InputRequested(request(), NOW).nodeId())
        .isEqualTo(request().nodeId());
    assertThat(new ExecutionEvent.InputEscalated(request(), "r", NOW).nodeId())
        .isEqualTo(request().nodeId());
    assertThat(
            new ExecutionEvent.InputAnswered("e", "s", "w", "r", responder, "d", NOW, "n").nodeId())
        .isEqualTo("n");
    assertThat(new ExecutionEvent.InputExpired("e", "s", "w", "r", NOW, "n").nodeId())
        .isEqualTo("n");
    assertThat(new ExecutionEvent.InputCancelled("e", "s", "w", "r", "x", NOW, "n").nodeId())
        .isEqualTo("n");
    assertThat(
            new ExecutionEvent.ControlOutcome("e", "s", "w", "C", null, true, null, NOW, "n")
                .nodeId())
        .isEqualTo("n");
  }

  @Test
  void runEventsNameNoNode() {
    assertThat(new ExecutionEvent.WorkflowStatusChanged("e", "s", "w", "RUNNING", NOW).nodeId())
        .isNull();
    assertThat(new ExecutionEvent.EventsMissed(1, NOW).nodeId()).isNull();
    assertThat(
            new ExecutionEvent.ControlOutcome("e", "s", "w", "C", null, true, null, NOW).nodeId())
        .isNull();
    assertThat(
            new ExecutionEvent.InputAnswered("e", "s", "w", "r", Responder.SYSTEM, "d", NOW)
                .nodeId())
        .isNull();
    assertThat(new ExecutionEvent.InputExpired("e", "s", "w", "r", NOW).nodeId()).isNull();
    assertThat(new ExecutionEvent.InputCancelled("e", "s", "w", "r", "x", NOW).nodeId()).isNull();
  }
}
