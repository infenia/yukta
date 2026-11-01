// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
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
 * Processor for pause node commands.
 *
 * <p>Applies backpressure to a specific node via its pause valve and emits an observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PauseNodeCommandProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlStore registry;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService taskTracker;

  /** Applies the command. */
  public Mono<Void> process(final PauseNodeCommand pause) {
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(pause.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + pause.executionId()));

          final ReactiveControlValve valve = control.nodePauseValves().get(pause.nodeId());
          if (valve == null) {
            throw new NotFoundException("Node not found or not pausable: " + pause.nodeId());
          }

          valve.pause();

          taskTracker.emitControlStateEvent(pause.executionId(), ControlState.NODE_PAUSED);

          log.atDebug()
              .addKeyValue("executionId", pause.executionId())
              .addKeyValue("nodeId", pause.nodeId())
              .addKeyValue("status", "NODE_PAUSED")
              .log("Paused node");
        });
  }
}
