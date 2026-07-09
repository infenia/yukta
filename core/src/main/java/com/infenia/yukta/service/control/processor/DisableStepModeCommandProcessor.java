// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for disable step mode commands.
 *
 * <p>Disables debug step-through mode on a node. The node returns to normal pause/resume behavior.
 * Emits an observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisableStepModeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof DisableStepModeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final DisableStepModeCommand disable = (DisableStepModeCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(disable.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + disable.executionId()));

          final AtomicBoolean stepMode = control.nodeStepModes().get(disable.nodeId());
          if (stepMode == null) {
            throw new IllegalArgumentException(
                "Node not found or does not support step mode: " + disable.nodeId());
          }

          stepMode.set(false);

          taskTracker.emitWorkflowStatusEvent(disable.executionId(), "STEP_MODE_DISABLED");

          log.atDebug()
              .addKeyValue("executionId", disable.executionId())
              .addKeyValue("nodeId", disable.nodeId())
              .addKeyValue("status", "STEP_MODE_DISABLED")
              .log("Disabled step mode");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
