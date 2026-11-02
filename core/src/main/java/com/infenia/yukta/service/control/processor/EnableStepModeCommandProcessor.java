// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for enable step mode commands.
 *
 * <p>Enables debug step-through mode on a node. The node automatically pauses until step signals
 * are sent. Emits an observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnableStepModeCommandProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlStore registry;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService taskTracker;

  /** Applies the command. */
  public Mono<Void> process(final EnableStepModeCommand enable) {
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(enable.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + enable.executionId()));

          final ReactiveControlValve valve = control.nodePauseValves().get(enable.nodeId());
          if (valve == null) {
            throw new NotFoundException(
                "Node not found or does not support step mode: " + enable.nodeId());
          }

          valve.enableStepMode();

          taskTracker.emitControlStateEvent(enable.executionId(), ControlState.STEP_MODE_ENABLED);

          log.atDebug()
              .addKeyValue("executionId", enable.executionId())
              .addKeyValue("nodeId", enable.nodeId())
              .addKeyValue("status", "STEP_MODE_ENABLED")
              .log("Enabled step mode");
        });
  }
}
