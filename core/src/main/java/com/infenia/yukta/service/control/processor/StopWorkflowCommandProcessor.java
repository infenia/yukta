// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Processor for stop workflow commands.
 *
 * <p>Safely stops the entire workflow execution by emitting the safe-stop sink. This also severs
 * the trigger plugin's stream, preventing any new input from being accepted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StopWorkflowCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof StopWorkflowCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final StopWorkflowCommand stop = (StopWorkflowCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(stop.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + stop.executionId()));

          control.safeStopSink().emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);

          taskTracker.emitWorkflowStatusEvent(stop.executionId(), "WORKFLOW_STOPPED");

          log.atDebug()
              .addKeyValue("executionId", stop.executionId())
              .addKeyValue("reason", stop.reason())
              .addKeyValue("status", "WORKFLOW_STOPPED")
              .log("Stopped workflow");
        });
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
