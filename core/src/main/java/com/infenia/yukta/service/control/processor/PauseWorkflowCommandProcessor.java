// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
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
public class PauseWorkflowCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof PauseWorkflowCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final PauseWorkflowCommand pause = (PauseWorkflowCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(pause.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + pause.executionId()));

          if (control.globalPauseValve() == null) {
            throw new IllegalStateException("Global pause valve not initialized");
          }

          control.globalPauseValve().pause();

          taskTracker.emitWorkflowStatusEvent(pause.executionId(), "PAUSED");

          log.atDebug()
              .addKeyValue("executionId", pause.executionId())
              .addKeyValue("status", "PAUSED")
              .log("Paused workflow");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
