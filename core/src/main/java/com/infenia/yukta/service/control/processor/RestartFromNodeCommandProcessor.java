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

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Processor for restart from node commands.
 *
 * <p>Stops the current execution and restarts from a specific node, using the last checkpoint
 * messages from parent nodes. Emits an observability event for the new execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestartFromNodeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The workflow orchestrator for restarting workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** The node checkpoint store for accessing node state checkpoints. */
  private final NodeCheckpointStore checkpointStore;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof RestartFromNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final RestartFromNodeCommand restart = (RestartFromNodeCommand) command;

    return Mono.fromSupplier(
            () ->
                registry
                    .findByExecutionId(restart.executionId())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                "Execution not found: " + restart.executionId())))
        .flatMap(
            control -> {
              final List<String> parentNodeIds =
                  control
                      .prepared()
                      .parentsList()
                      .getOrDefault(restart.fromNodeId(), List.of())
                      .stream()
                      .map(WorkflowNode::nodeId)
                      .toList();

              return Flux.fromIterable(parentNodeIds)
                  .flatMap(
                      parentNodeId ->
                          checkpointStore
                              .get(control.executionId(), parentNodeId)
                              .doOnNext(
                                  v ->
                                      log.atDebug()
                                          .addKeyValue("parentNodeId", parentNodeId)
                                          .log("Loaded checkpoint"))
                              .onErrorResume(
                                  e -> {
                                    log.atWarn()
                                        .addKeyValue("parentNodeId", parentNodeId)
                                        .log("No checkpoint for parent node");
                                    return Mono.empty();
                                  }))
                  .collectMap(Message::getSourceNodeId, m -> m)
                  .flatMap(
                      parentCheckpoints -> {
                        registry.unregister(control.executionId());
                        control.safeStopSink().emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
                        checkpointStore.clear(control.executionId());

                        final String newExecutionId = UUID.randomUUID().toString();
                        @SuppressWarnings("unchecked")
                        final Map<String, Message<?>> checkpoints =
                            (Map<String, Message<?>>) (Map<?, ?>) parentCheckpoints;
                        return orchestrator
                            .restartFromNode(
                                control.sessionId(),
                                control.workflowId(),
                                control.executionId(),
                                newExecutionId,
                                control.prepared(),
                                restart.fromNodeId(),
                                checkpoints)
                            .doOnSuccess(
                                ignored -> {
                                  taskTracker.emitWorkflowStatusEvent(newExecutionId, "RUNNING");
                                  log.atInfo()
                                      .addKeyValue("oldExecutionId", restart.executionId())
                                      .addKeyValue("newExecutionId", newExecutionId)
                                      .addKeyValue("fromNodeId", restart.fromNodeId())
                                      .addKeyValue("status", "RUNNING")
                                      .log("Restarted execution from node");
                                })
                            .then(Mono.<WorkflowDirective>empty());
                      })
                  .onErrorResume(
                      e -> {
                        log.atError()
                            .addKeyValue("executionId", restart.executionId())
                            .addKeyValue("fromNodeId", restart.fromNodeId())
                            .setCause(e)
                            .log("Restart from node failed");
                        return Mono.empty();
                      });
            })
        .onErrorResume(
            e -> {
              log.atError()
                  .addKeyValue("executionId", restart.executionId())
                  .setCause(e)
                  .log("Failed to find execution for restart");
              return Mono.empty();
            });
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
