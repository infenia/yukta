// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Restarts of runs, completed once the command processor reports the outcome. */
@Slf4j
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class RestartCoordinator extends ControlDispatch {

  /** Timeout for awaiting a restart's real outcome from its command processor. */
  private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(30);

  /** Pending restart completions, keyed by the pre-generated new execution ID. */
  private final Map<String, Sinks.One<String>> pendingRestarts = new ConcurrentHashMap<>();

  /** Finished executions that can still be restarted. */
  private final ExecutionArchive executionArchive;

  /** Makes a repeated restart of the same execution return the first new execution. */
  private final StartIdempotency idempotency;

  /* default */ RestartCoordinator(
      final ControlBusGateway gateway,
      final ExecutionControlRegistry executionControlRegistry,
      final ExecutionArchive executionArchive,
      final StartIdempotency idempotency) {
    super(gateway, executionControlRegistry);
    this.executionArchive = executionArchive;
    this.idempotency = idempotency;
  }

  /* default */ Mono<String> restartWorkflow(final String executionId) {
    return awaitRestart(
        executionId,
        "restart",
        newId -> new RestartCommand(executionId, newId),
        newExecutionId ->
            log.atInfo()
                .addKeyValue("executionId", executionId)
                .addKeyValue("newExecutionId", newExecutionId)
                .log("Restarting workflow"),
        newId ->
            log.atInfo()
                .addKeyValue("oldExecutionId", executionId)
                .addKeyValue("newExecutionId", newId)
                .log("Workflow restarted successfully"),
        err ->
            log.atError()
                .setCause(err)
                .addKeyValue("executionId", executionId)
                .log("Failed to restart workflow"));
  }

  /* default */ Mono<String> restartFromNode(final String executionId, final String fromNodeId) {
    return awaitRestart(
        executionId,
        "restart-from\0" + fromNodeId,
        newId -> new RestartFromNodeCommand(executionId, fromNodeId, newId),
        newExecutionId ->
            log.atInfo()
                .addKeyValue("executionId", executionId)
                .addKeyValue("fromNodeId", fromNodeId)
                .addKeyValue("newExecutionId", newExecutionId)
                .log("Restarting workflow from node"),
        newId ->
            log.atInfo()
                .addKeyValue("oldExecutionId", executionId)
                .addKeyValue("fromNodeId", fromNodeId)
                .addKeyValue("newExecutionId", newId)
                .log("Workflow restarted from node successfully"),
        err ->
            log.atError()
                .setCause(err)
                .addKeyValue("executionId", executionId)
                .addKeyValue("fromNodeId", fromNodeId)
                .log("Failed to restart workflow from node"));
  }

  /**
   * A repeat of the same restart within the idempotency window (an agent that did not see the
   * answer) returns the first new execution instead of starting another; a failed restart is
   * forgotten so it can be retried.
   */
  private Mono<String> awaitRestart(
      final String executionId,
      final String kind,
      final java.util.function.Function<String, ExecutionControlCommand> commandFactory,
      final java.util.function.Consumer<String> onSubscribeLogger,
      final java.util.function.Consumer<String> onSuccessLogger,
      final java.util.function.Consumer<Throwable> onErrorLogger) {
    return idempotency.once(
        executionId,
        kind,
        () ->
            sendRestart(
                executionId, commandFactory, onSubscribeLogger, onSuccessLogger, onErrorLogger));
  }

  private Mono<String> sendRestart(
      final String executionId,
      final java.util.function.Function<String, ExecutionControlCommand> commandFactory,
      final java.util.function.Consumer<String> onSubscribeLogger,
      final java.util.function.Consumer<String> onSuccessLogger,
      final java.util.function.Consumer<Throwable> onErrorLogger) {
    return Mono.defer(
            () -> {
              if (executionArchive.find(executionId).isEmpty()) {
                findExecutionOrThrow(executionId);
              }
              final String newExecutionId = UUID.randomUUID().toString();
              final Sinks.One<String> sink = Sinks.one();
              pendingRestarts.put(newExecutionId, sink);
              return executeCommand(
                      buildCommand(
                          commandFactory.apply(newExecutionId), CONTROL_COMMAND_PRIORITY + 20))
                  .doOnSubscribe(_ -> onSubscribeLogger.accept(newExecutionId))
                  .then(sink.asMono())
                  .timeout(RESTART_TIMEOUT)
                  .doFinally(_ -> pendingRestarts.remove(newExecutionId));
            })
        .doOnSuccess(onSuccessLogger)
        .doOnError(onErrorLogger);
  }

  /* default */ void completeRestartSuccess(final String newExecutionId) {
    final Sinks.One<String> sink = pendingRestarts.remove(newExecutionId);
    if (sink == null) {
      log.atDebug()
          .addKeyValue("newExecutionId", newExecutionId)
          .log("Restart completion arrived after caller already timed out");
      return;
    }
    sink.tryEmitValue(newExecutionId);
  }

  /* default */ void completeRestartFailure(final String newExecutionId, final Throwable error) {
    final Sinks.One<String> sink = pendingRestarts.remove(newExecutionId);
    if (sink == null) {
      log.atDebug()
          .addKeyValue("newExecutionId", newExecutionId)
          .log("Restart failure arrived after caller already timed out");
      return;
    }
    sink.tryEmitError(error);
  }
}
