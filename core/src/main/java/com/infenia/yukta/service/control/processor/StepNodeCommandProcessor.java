// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for step node commands.
 *
 * <p>Signals the next step when a node is in step-through debug mode. Allows exactly one element to
 * pass before blocking again. Emits an observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepNodeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof StepNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final StepNodeCommand step = (StepNodeCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(step.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + step.executionId()));

          final ReactiveControlValve valve = control.nodePauseValves().get(step.nodeId());
          if (valve == null) {
            throw new NotFoundException(
                "Node not found or does not support stepping: " + step.nodeId());
          }
          if (!valve.step()) {
            throw new IllegalStateException("Node is not in step mode: " + step.nodeId());
          }

          taskTracker.emitControlStateEvent(step.executionId(), ControlState.NODE_STEPPED);

          log.atDebug()
              .addKeyValue("executionId", step.executionId())
              .addKeyValue("nodeId", step.nodeId())
              .addKeyValue("status", "NODE_STEPPED")
              .log("Stepped node");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
