// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.runtime.ExecutionControl;
import com.infenia.yukta.service.runtime.store.ExecutionControlStore;
import com.infenia.yukta.service.runtime.valve.ReactiveControlValve;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Applies the node commands that act on a node's pause valve: pause, resume, step mode on and off,
 * and one step. They differ only in the valve method, the reason given when the node has no valve
 * and the {@link ControlState} emitted, so they share one lookup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeValveCommands {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlStore registry;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService taskTracker;

  /** Pauses the node's valve. */
  public Mono<Void> process(final PauseNodeCommand pause) {
    return apply(
        pause, "not pausable", ReactiveControlValve::pause, ControlState.NODE_PAUSED, "Paused");
  }

  /** Resumes the node's valve. */
  public Mono<Void> process(final ResumeNodeCommand resume) {
    return apply(
        resume, "not pausable", ReactiveControlValve::resume, ControlState.NODE_RESUMED, "Resumed");
  }

  /** Puts the node in step-through mode. */
  public Mono<Void> process(final EnableStepModeCommand enable) {
    return apply(
        enable,
        "does not support step mode",
        ReactiveControlValve::enableStepMode,
        ControlState.STEP_MODE_ENABLED,
        "Enabled step mode on");
  }

  /** Takes the node out of step-through mode. */
  public Mono<Void> process(final DisableStepModeCommand disable) {
    return apply(
        disable,
        "does not support step mode",
        ReactiveControlValve::disableStepMode,
        ControlState.STEP_MODE_DISABLED,
        "Disabled step mode on");
  }

  /** Lets one element pass a node that is in step mode. */
  public Mono<Void> process(final StepNodeCommand step) {
    return apply(
        step,
        "does not support stepping",
        valve -> {
          if (!valve.step()) {
            throw new IllegalStateException("Node is not in step mode: " + step.nodeId());
          }
        },
        ControlState.NODE_STEPPED,
        "Stepped");
  }

  private Mono<Void> apply(
      final ExecutionControlCommand command,
      final String unsupported,
      final Consumer<ReactiveControlValve> action,
      final ControlState state,
      final String verb) {
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(command.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + command.executionId()));
          final ReactiveControlValve valve = control.nodePauseValves().get(command.nodeId());
          if (valve == null) {
            throw new NotFoundException(
                "Node not found or " + unsupported + ": " + command.nodeId());
          }
          action.accept(valve);
          taskTracker.emitControlStateEvent(command.executionId(), state);
          log.atDebug()
              .addKeyValue("executionId", command.executionId())
              .addKeyValue("nodeId", command.nodeId())
              .addKeyValue("status", state.name())
              .log("{} node", verb);
        });
  }
}
