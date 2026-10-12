// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.archive.ArchivedExecution;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.RestartPlan;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

  /** The execution control registry, used to stop the old run if it is still going. */
  private final ExecutionControlRegistry registry;

  /** Keeps recent executions, running or finished, restartable. */
  private final ExecutionArchive archive;

  /** The orchestrator that runs the restarted execution. */
  private final WorkflowOrchestrator orchestrator;

  /** The recorded output of every node of the old execution. */
  private final NodeCheckpointStore checkpointStore;

  /** Refuses a restart of a finished run that could neither run nor be queued. */
  private final ExecutionScheduler scheduler;

  /** Receives the outcome of the restart. */
  private final RestartCompletionSink completionSink;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof RestartFromNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final RestartFromNodeCommand restart = (RestartFromNodeCommand) command;

    return Mono.defer(() -> restart(restart))
        .then(Mono.<WorkflowDirective>empty())
        .onErrorResume(
            e -> {
              log.atError()
                  .addKeyValue("executionId", restart.executionId())
                  .addKeyValue("fromNodeId", restart.fromNodeId())
                  .setCause(e)
                  .log("Restart from node failed");
              completionSink.completeRestartFailure(restart.newExecutionId(), e);
              return Mono.error(e);
            });
  }

  private Mono<Void> restart(final RestartFromNodeCommand restart) {
    final ArchivedExecution archived =
        archive
            .find(restart.executionId())
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Execution not found or no longer restartable: " + restart.executionId()));
    if (registry.findByExecutionId(restart.executionId()).isEmpty()) {
      // A running run gives up its slot when stopped, so only a finished one is checked.
      scheduler.assertCanAccept(archived.sessionId(), archived.workflowId());
    }
    final RestartPlan plan = RestartPlan.compute(archived.prepared(), restart.fromNodeId());
    final boolean live = registry.findByExecutionId(restart.executionId()).isPresent();
    for (final String nodeId : plan.boundary()) {
      if (live && !checkpointStore.isFinished(restart.executionId(), nodeId)) {
        throw new IllegalArgumentException(
            "Cannot restart from '"
                + restart.fromNodeId()
                + "': node '"
                + nodeId
                + "' has not finished yet, so its output is still being recorded");
      }
      if (!checkpointStore.isComplete(restart.executionId(), nodeId)) {
        throw new IllegalArgumentException(
            "Cannot restart from '"
                + restart.fromNodeId()
                + "': the recorded output of node '"
                + nodeId
                + "' was larger than the retention limit and is incomplete");
      }
    }

    return Flux.fromIterable(plan.boundary())
        .concatMap(
            nodeId ->
                checkpointStore
                    .getAll(restart.executionId(), nodeId)
                    .collectList()
                    .map(messages -> Map.entry(nodeId, messages)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .doOnNext(outputs -> start(restart, archived, outputs))
        .then();
  }

  private void start(
      final RestartFromNodeCommand restart,
      final ArchivedExecution archived,
      final Map<String, List<Message<?>>> outputs) {
    // If the old run is still going it is stopped first, so it cannot repeat side effects.
    registry
        .findByExecutionId(restart.executionId())
        .ifPresent(
            control -> {
              control.safeStopSink().tryEmitEmpty();
              registry.unregister(control.executionId());
            });

    orchestrator
        .restartFromNode(
            archived.sessionId(),
            archived.workflowId(),
            restart.executionId(),
            restart.newExecutionId(),
            archived.prepared(),
            restart.fromNodeId(),
            outputs)
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("oldExecutionId", restart.executionId())
                    .addKeyValue("newExecutionId", restart.newExecutionId())
                    .log("Restarted execution from node failed"))
        .onErrorComplete()
        .subscribe();

    log.atInfo()
        .addKeyValue("oldExecutionId", restart.executionId())
        .addKeyValue("newExecutionId", restart.newExecutionId())
        .addKeyValue("fromNodeId", restart.fromNodeId())
        .addKeyValue("definitionVersion", archived.definitionVersion())
        .log("Restarted execution from node");
    completionSink.completeRestartSuccess(restart.newExecutionId());
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
