// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Processor for restart from node commands.
 *
 * <p>Stops the current execution and restarts from a specific node, using the last checkpoint
 * messages from parent nodes. The new execution is detached ({@code
 * subscribeOn(boundedElastic()).subscribe()}) so this processor reports completion as soon as the
 * new execution is subscribed, not once it finishes.
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

  /** Reports the outcome of a restart back to the awaiting gateway caller. */
  private final RestartCompletionSink completionSink;

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
                  .doOnNext(
                      parentCheckpoints -> {
                        final Sinks.EmitResult stopResult = control.safeStopSink().tryEmitEmpty();
                        if (stopResult.isFailure()) {
                          throw new IllegalStateException(
                              "Failed to emit stop signal: " + stopResult);
                        }
                        registry.unregister(control.executionId());
                        checkpointStore.clear(control.executionId());

                        @SuppressWarnings("unchecked")
                        final Map<String, Message<?>> checkpoints =
                            (Map<String, Message<?>>) (Map<?, ?>) parentCheckpoints;
                        orchestrator
                            .restartFromNode(
                                control.sessionId(),
                                control.workflowId(),
                                control.executionId(),
                                restart.newExecutionId(),
                                control.prepared(),
                                restart.fromNodeId(),
                                checkpoints)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnError(
                                err ->
                                    log.atError()
                                        .setCause(err)
                                        .addKeyValue("oldExecutionId", restart.executionId())
                                        .addKeyValue("newExecutionId", restart.newExecutionId())
                                        .log("Restarted execution from node failed"))
                            .subscribe();

                        log.atInfo()
                            .addKeyValue("oldExecutionId", restart.executionId())
                            .addKeyValue("newExecutionId", restart.newExecutionId())
                            .addKeyValue("fromNodeId", restart.fromNodeId())
                            .log("Restarted execution from node");
                        completionSink.completeRestartSuccess(restart.newExecutionId());
                      });
            })
        .then(Mono.<WorkflowDirective>empty())
        .onErrorResume(
            e -> {
              log.atError()
                  .addKeyValue("executionId", restart.executionId())
                  .addKeyValue("fromNodeId", restart.fromNodeId())
                  .setCause(e)
                  .log("Restart from node failed");
              completionSink.completeRestartFailure(restart.newExecutionId(), e);
              return Mono.empty();
            });
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
