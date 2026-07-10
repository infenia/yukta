// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Processor for restart commands.
 *
 * <p>Stops the current execution and restarts the entire workflow from the beginning with the
 * original payload. The new execution is detached ({@code
 * subscribeOn(boundedElastic()).subscribe()}) so this processor reports completion as soon as the
 * new execution is subscribed, not once it finishes — matching how {@code
 * DefaultControlBusGateway.prepareAndExecute} starts a normal (non-restart) execution.
 *
 * <p>Restarts are serialized per execution ID: only one restart can proceed at a time for a given
 * execution. The handoff is atomic: unregister and stop signals are emitted synchronously within
 * the Mono.defer block before the new execution Mono is even created, ensuring these operations
 * complete before the replacement begins. If unregister/stop fails, the exception propagates
 * immediately and prevents the new execution from being subscribed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestartCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The workflow orchestrator for restarting workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** Reports the outcome of a restart back to the awaiting gateway caller. */
  private final RestartCompletionSink completionSink;

  /** Per-execution restart serialization: tracks which executions are currently being restarted. */
  private final Map<String, Object> restartInProgress = new ConcurrentHashMap<>();

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof RestartCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final RestartCommand restart = (RestartCommand) command;

    return serializeRestart(restart);
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private Mono<WorkflowDirective> serializeRestart(final RestartCommand restart) {
    final Object lock = new Object();
    final boolean acquired = restartInProgress.putIfAbsent(restart.executionId(), lock) == null;

    if (!acquired) {
      log.atWarn()
          .addKeyValue("executionId", restart.executionId())
          .log("Restart already in progress, rejecting duplicate");
      completionSink.completeRestartFailure(
          restart.newExecutionId(),
          new IllegalStateException("Restart already in progress for this execution"));
      return Mono.empty();
    }

    return executeAtomicRestart(restart)
        .doFinally(_ -> restartInProgress.remove(restart.executionId()));
  }

  private Mono<WorkflowDirective> executeAtomicRestart(final RestartCommand restart) {
    return Mono.fromSupplier(
            () ->
                registry
                    .findByExecutionId(restart.executionId())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                "Execution not found: " + restart.executionId())))
        .flatMap(control -> performAtomicHandoff(control, restart))
        .then(Mono.<WorkflowDirective>empty())
        .onErrorResume(
            e -> {
              log.atError()
                  .addKeyValue("executionId", restart.executionId())
                  .setCause(e)
                  .log("Restart failed");
              completionSink.completeRestartFailure(restart.newExecutionId(), e);
              return Mono.empty();
            });
  }

  private Mono<Void> performAtomicHandoff(
      final ExecutionControl control, final RestartCommand restart) {
    return Mono.fromCallable(
            () -> {
              final Sinks.EmitResult stopResult = control.safeStopSink().tryEmitEmpty();
              if (stopResult.isFailure()) {
                throw new IllegalStateException("Failed to emit stop signal: " + stopResult);
              }
              return stopResult;
            })
        .doOnNext(_ -> registry.unregister(control.executionId()))
        .flatMap(
            _ -> {
              final Mono<Void> newExecution =
                  orchestrator.execute(
                      control.sessionId(),
                      control.workflowId(),
                      restart.newExecutionId(),
                      control.prepared(),
                      control.payload());

              newExecution
                  .subscribeOn(Schedulers.boundedElastic())
                  .doOnError(
                      err ->
                          log.atError()
                              .setCause(err)
                              .addKeyValue("oldExecutionId", restart.executionId())
                              .addKeyValue("newExecutionId", restart.newExecutionId())
                              .log("Restarted execution failed"))
                  .subscribe();

              log.atInfo()
                  .addKeyValue("oldExecutionId", restart.executionId())
                  .addKeyValue("newExecutionId", restart.newExecutionId())
                  .addKeyValue("workflowId", control.workflowId())
                  .log("Restarted execution");
              completionSink.completeRestartSuccess(restart.newExecutionId());
              return Mono.empty();
            });
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
