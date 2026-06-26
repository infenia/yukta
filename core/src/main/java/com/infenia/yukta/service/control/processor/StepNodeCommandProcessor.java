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
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
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
  private final DefaultTaskTrackerService taskTracker;

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
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + step.executionId()));

          final Sinks.Many<Void> stepSink = control.nodeStepSinks().get(step.nodeId());
          if (stepSink == null) {
            throw new IllegalArgumentException(
                "Node not found or does not support stepping: " + step.nodeId());
          }

          stepSink.emitNext(null, Sinks.EmitFailureHandler.FAIL_FAST);

          taskTracker.emitWorkflowStatusEvent(step.executionId(), "NODE_STEPPED");

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
