/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
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
public class PauseNodeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof PauseNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final PauseNodeCommand pause = (PauseNodeCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(pause.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + pause.executionId()));

          final ReactiveControlValve valve = control.nodePauseValves().get(pause.nodeId());
          if (valve == null) {
            throw new IllegalArgumentException("Node not found or not pausable: " + pause.nodeId());
          }

          valve.pause();

          taskTracker.emitWorkflowStatusEvent(pause.executionId(), "NODE_PAUSED");

          log.atDebug()
              .addKeyValue("executionId", pause.executionId())
              .addKeyValue("nodeId", pause.nodeId())
              .addKeyValue("status", "NODE_PAUSED")
              .log("Paused node");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
