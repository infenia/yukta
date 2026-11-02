// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for pause workflow commands.
 *
 * <p>Applies backpressure to all nodes in an execution via the global pause valve and emits an
 * observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PauseWorkflowCommandProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlStore registry;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService taskTracker;

  /** Applies the command. */
  public Mono<Void> process(final PauseWorkflowCommand pause) {
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(pause.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + pause.executionId()));

          if (control.globalPauseValve() == null) {
            throw new IllegalStateException("Global pause valve not initialized");
          }

          control.globalPauseValve().pause();

          taskTracker.emitControlStateEvent(pause.executionId(), ControlState.PAUSED);

          log.atDebug()
              .addKeyValue("executionId", pause.executionId())
              .addKeyValue("status", "PAUSED")
              .log("Paused workflow");
        });
  }
}
