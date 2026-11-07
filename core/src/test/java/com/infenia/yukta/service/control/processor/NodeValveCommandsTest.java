// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class NodeValveCommandsTest {

  private static final String RUN = "exec-1";
  private static final String NODE = "node-1";

  private final ExecutionControlStore registry = mock(ExecutionControlStore.class);
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final ExecutionControl control = mock(ExecutionControl.class);
  private final ReactiveControlValve valve = new ReactiveControlValve();
  private final NodeValveCommands commands = new NodeValveCommands(registry, tracker);

  @BeforeEach
  void setUp() {
    when(registry.findByExecutionId(RUN)).thenReturn(Optional.of(control));
    when(control.nodePauseValves()).thenReturn(Map.of(NODE, valve));
  }

  @Test
  void process_pauseThenResume_flipsValveAndEmitsState() {
    StepVerifier.create(commands.process(new PauseNodeCommand(RUN, NODE))).verifyComplete();
    assertThat(valve.isPaused()).isTrue();
    verify(tracker).emitControlStateEvent(RUN, ControlState.NODE_PAUSED);

    StepVerifier.create(commands.process(new ResumeNodeCommand(RUN, NODE))).verifyComplete();
    assertThat(valve.isPaused()).isFalse();
    verify(tracker).emitControlStateEvent(RUN, ControlState.NODE_RESUMED);
  }

  @Test
  void process_stepModeOnStepOff_emitsEachState() {
    StepVerifier.create(commands.process(new EnableStepModeCommand(RUN, NODE))).verifyComplete();
    verify(tracker).emitControlStateEvent(RUN, ControlState.STEP_MODE_ENABLED);

    StepVerifier.create(commands.process(new StepNodeCommand(RUN, NODE))).verifyComplete();
    verify(tracker).emitControlStateEvent(RUN, ControlState.NODE_STEPPED);

    StepVerifier.create(commands.process(new DisableStepModeCommand(RUN, NODE))).verifyComplete();
    verify(tracker).emitControlStateEvent(RUN, ControlState.STEP_MODE_DISABLED);
  }

  @Test
  void process_stepOutsideStepMode_failsWithoutEvent() {
    StepVerifier.create(commands.process(new StepNodeCommand(RUN, NODE)))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Node is not in step mode: " + NODE))
        .verify();
  }

  @Test
  void process_unknownExecution_failsNotFound() {
    when(registry.findByExecutionId("gone")).thenReturn(Optional.empty());
    StepVerifier.create(commands.process(new PauseNodeCommand("gone", NODE)))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Execution not found: gone"))
        .verify();
  }

  @Test
  void process_unknownNode_namesWhatTheCommandNeeds() {
    StepVerifier.create(commands.process(new PauseNodeCommand(RUN, "x")))
        .expectErrorMessage("Node not found or not pausable: x")
        .verify();
    StepVerifier.create(commands.process(new EnableStepModeCommand(RUN, "x")))
        .expectErrorMessage("Node not found or does not support step mode: x")
        .verify();
    StepVerifier.create(commands.process(new StepNodeCommand(RUN, "x")))
        .expectErrorMessage("Node not found or does not support stepping: x")
        .verify();
  }
}
