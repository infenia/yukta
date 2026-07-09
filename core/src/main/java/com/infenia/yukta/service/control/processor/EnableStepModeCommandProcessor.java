// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
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
 * Processor for enable step mode commands.
 *
 * <p>Enables debug step-through mode on a node. The node automatically pauses until step signals
 * are sent. Emits an observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnableStepModeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof EnableStepModeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final EnableStepModeCommand enable = (EnableStepModeCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(enable.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + enable.executionId()));

          final AtomicBoolean stepMode = control.nodeStepModes().get(enable.nodeId());
          if (stepMode == null) {
            throw new IllegalArgumentException(
                "Node not found or does not support step mode: " + enable.nodeId());
          }

          stepMode.set(true);

          taskTracker.emitWorkflowStatusEvent(enable.executionId(), "STEP_MODE_ENABLED");

          log.atDebug()
              .addKeyValue("executionId", enable.executionId())
              .addKeyValue("nodeId", enable.nodeId())
              .addKeyValue("status", "STEP_MODE_ENABLED")
              .log("Enabled step mode");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
