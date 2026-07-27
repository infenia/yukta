// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Default implementation of the {@link ControlBusGateway}.
 *
 * <p>Delegates low-level plugin management and message operations to {@link ControlBusService}, and
 * high-level control commands and observability operations to {@link DefaultTaskTrackerService}.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals"
})
public class DefaultControlBusGateway implements ControlBusGateway, RestartCompletionSink {

  /** Source identifier for control bus operations. */
  private static final String CONTROL_BUS_SOURCE = "CONTROL_BUS";

  /** Priority level for control commands. */
  private static final int CONTROL_COMMAND_PRIORITY = 100;

  /** Timeout for awaiting a restart's real outcome from its command processor. */
  private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(30);

  /** Pending restart completions, keyed by the pre-generated new execution ID. */
  private final Map<String, Sinks.One<String>> pendingRestarts = new ConcurrentHashMap<>();

  /** The control bus service for low-level plugin and message operations. */
  private final ControlBusService controlBusService;

  /** The task tracker service for tracking execution tasks. */
  private final DefaultTaskTrackerService taskTracker;

  /** The execution control registry for managing active executions. */
  private final ExecutionControlRegistry executionControlRegistry;

  /** The workflow orchestrator for executing workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** The workflow definition store for accessing workflow definitions. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  /** The prepared workflow cache for caching compiled workflows. */
  private final PreparedWorkflowCache preparedWorkflowCache;

  private <T extends ExecutionControlCommand> Message<T> buildCommand(
      final T command, final int priority) {
    return DefaultMessage.create(null, command)
        .withSourceNodeId(CONTROL_BUS_SOURCE)
        .withPriority(priority)
        .withControl(true);
  }

  // --- Plugin & Message Management ---

  @Override
  public <T> Mono<Void> emit(final Message<T> signal) {
    return Mono.defer(
            () -> {
              log.atTrace()
                  .addKeyValue("sourceNodeId", signal.getSourceNodeId())
                  .addKeyValue("priority", signal.getPriority())
                  .log("Emitting message to control bus");
              return controlBusService.emit(signal);
            })
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sourceNodeId", signal.getSourceNodeId())
                    .log("Message emitted successfully"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("sourceNodeId", signal.getSourceNodeId())
                    .log("Failed to emit message"));
  }

  @Override
  public void registerPlugin(final String workflowId, final String nodeId, final Plugin plugin) {
    log.atInfo()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .addKeyValue("pluginType", plugin.getClass().getSimpleName())
        .log("Registering plugin");
    controlBusService.registerPlugin(workflowId, nodeId, plugin);
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Plugin registered successfully");
  }

  @Override
  public void unregisterPlugin(final String workflowId, final String nodeId) {
    log.atInfo()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Unregistering plugin");
    controlBusService.unregisterPlugin(workflowId, nodeId);
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Plugin unregistered successfully");
  }

  @Override
  public Mono<Message<?>> sendCommand(
      final String workflowId, final String nodeId, final Message<?> command) {
    return Mono.defer(
            () -> {
              log.atInfo()
                  .addKeyValue("workflowId", workflowId)
                  .addKeyValue("nodeId", nodeId)
                  .log("Sending command to node");
              return controlBusService.sendCommand(workflowId, nodeId, command);
            })
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Command sent and response received"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to send command"));
  }

  // --- Configuration & Preparation ---

  @Override
  public Mono<Void> compileAndCacheWorkflow(
      final String sessionId, final WorkflowDefinition workflowDefinition) {
    return Mono.defer(
            () -> {
              log.atInfo()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflowId", workflowDefinition.workflowId())
                  .addKeyValue("nodeCount", workflowDefinition.nodes().size())
                  .addKeyValue("edgeCount", workflowDefinition.edges().size())
                  .log("Compiling and caching workflow");
              return controlBusService.compileAndCacheWorkflow(sessionId, workflowDefinition);
            })
        .doOnSuccess(
            _ ->
                log.atInfo()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowDefinition.workflowId())
                    .log("Workflow compiled and cached successfully"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowDefinition.workflowId())
                    .log("Failed to compile and cache workflow"));
  }

  // --- Execution Control ---

  @Override
  public Mono<String> startWorkflow(final String sessionId, final String workflowId) {
    log.atInfo()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("workflowId", workflowId)
        .log("Starting workflow execution");

    return workflowDefinitionStore
        .find(sessionId, workflowId)
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.atWarn()
                      .addKeyValue("sessionId", sessionId)
                      .addKeyValue("workflowId", workflowId)
                      .log("Workflow definition not found");
                  return Mono.error(
                      new IllegalArgumentException(
                          "Workflow not found for session: "
                              + sessionId
                              + ", workflow: "
                              + workflowId));
                }))
        .flatMap(
            def -> {
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflowId", workflowId)
                  .addKeyValue("nodeCount", def.nodes().size())
                  .addKeyValue("edgeCount", def.edges().size())
                  .log("Workflow definition found and loaded");

              final String executionId = UUID.randomUUID().toString();
              return prepareAndExecute(sessionId, workflowId, def, executionId);
            })
        .doOnSuccess(
            executionId ->
                log.atInfo()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("executionId", executionId)
                    .log("Workflow execution started successfully"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .log("Failed to start workflow execution"));
  }

  private Mono<String> prepareAndExecute(
      final String sessionId,
      final String workflowId,
      final WorkflowDefinition def,
      final String executionId) {
    return Mono.defer(
            () -> {
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflowId", workflowId)
                  .addKeyValue("executionId", executionId)
                  .log("Preparing workflow for execution");

              final java.util.Optional<PreparedWorkflow> cached =
                  preparedWorkflowCache.get(sessionId, workflowId);
              if (cached.isPresent()) {
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("executionId", executionId)
                    .log("Using cached PreparedWorkflow");
                return Mono.just(cached.get());
              } else {
                return orchestrator
                    .prepareWorkflow(def)
                    .doOnNext(
                        prepared -> {
                          log.atDebug()
                              .addKeyValue("executionId", executionId)
                              .log("Workflow prepared and caching");
                          preparedWorkflowCache.put(sessionId, workflowId, prepared);
                        });
              }
            })
        .flatMap(
            prepared -> {
              orchestrator
                  .execute(sessionId, workflowId, executionId, prepared, Map.of())
                  .subscribeOn(Schedulers.boundedElastic())
                  .doOnError(
                      err ->
                          log.atError()
                              .setCause(err)
                              .addKeyValue("executionId", executionId)
                              .log("Async workflow execution failed"))
                  .subscribe();
              return Mono.just(executionId);
            });
  }

  @Override
  public <T extends ExecutionControlCommand> Mono<Void> executeCommand(final Message<T> command) {
    @SuppressWarnings({"PMD.LawOfDemeter"})
    final var commandType = command.getPayload().getClass().getSimpleName();
    log.atDebug().addKeyValue("commandType", commandType).log("Executing control command");
    return emit(command);
  }

  private ExecutionControl findExecutionOrThrow(final String executionId) {
    return executionControlRegistry
        .findByExecutionId(executionId)
        .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
  }

  @Override
  public Mono<Void> pauseWorkflow(final String executionId) {
    return Mono.fromSupplier(() -> findExecutionOrThrow(executionId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(new PauseWorkflowCommand(executionId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ -> log.atInfo().addKeyValue("executionId", executionId).log("Pausing workflow"))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .log("Workflow pause command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .log("Failed to pause workflow"));
  }

  @Override
  public Mono<Void> resumeWorkflow(final String executionId) {
    return Mono.fromSupplier(() -> findExecutionOrThrow(executionId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(new ResumeWorkflowCommand(executionId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ -> log.atInfo().addKeyValue("executionId", executionId).log("Resuming workflow"))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .log("Workflow resume command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .log("Failed to resume workflow"));
  }

  @Override
  public Mono<Void> pauseNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new PauseNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Pausing node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node pause command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to pause node"));
  }

  @Override
  public Mono<Void> resumeNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new ResumeNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Resuming node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node resume command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to resume node"));
  }

  private ExecutionControl requireNodeControl(final String executionId, final String nodeId) {
    final ExecutionControl control = findExecutionOrThrow(executionId);
    if (!control.nodePauseValves().containsKey(nodeId)) {
      throw new IllegalArgumentException("Node not found: " + nodeId);
    }
    return control;
  }

  @Override
  public Mono<Void> stopNode(
      final String executionId, final String nodeId, final boolean immediate, final String reason) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new StopNodeCommand(executionId, nodeId, immediate, reason),
                        CONTROL_COMMAND_PRIORITY + 10)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("immediate", immediate)
                    .addKeyValue("reason", reason)
                    .log("Stopping node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node stop command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to stop node"));
  }

  @Override
  public Mono<List<String>> stopWorkflow(
      final String sessionId, final String workflowId, final String reason) {
    return Mono.fromCallable(
            () -> {
              final var allExecutions =
                  executionControlRegistry.findAllActiveByWorkflow(sessionId, workflowId);
              if (allExecutions.isEmpty()) {
                throw new IllegalArgumentException(
                    "No active execution found for session: "
                        + sessionId
                        + ", workflow: "
                        + workflowId);
              }
              return allExecutions;
            })
        .flatMapMany(
            executions ->
                Flux.fromIterable(executions)
                    .flatMap(
                        control ->
                            executeCommand(
                                    buildCommand(
                                        new StopWorkflowCommand(control.executionId(), reason),
                                        CONTROL_COMMAND_PRIORITY + 20))
                                .thenReturn(control.executionId())))
        .collectList()
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("reason", reason)
                    .log("Stopping all workflow executions"))
        .doOnSuccess(
            stoppedIds ->
                log.atInfo()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("count", stoppedIds.size())
                    .log("All workflow stop commands executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .log("Failed to stop workflow executions"));
  }

  @Override
  public Mono<String> stopExecution(final String executionId, final String reason) {
    return Mono.fromSupplier(() -> findExecutionOrThrow(executionId))
        .flatMap(
            control ->
                executeCommand(
                        buildCommand(
                            new StopWorkflowCommand(control.executionId(), reason),
                            CONTROL_COMMAND_PRIORITY + 20))
                    .thenReturn(control.executionId()))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("reason", reason)
                    .log("Stopping specific execution"))
        .doOnSuccess(
            stoppedId ->
                log.atInfo()
                    .addKeyValue("executionId", stoppedId)
                    .log("Execution stop command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .log("Failed to stop execution"));
  }

  @Override
  public Mono<Void> skipNode(final String executionId, final String nodeId, final boolean skip) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new SkipNodeCommand(executionId, nodeId, skip), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("skip", skip)
                    .log("Toggling node skip"))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("skip", skip)
                    .log("Node skip command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to skip node"));
  }

  @Override
  public Mono<Void> enableStepMode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new EnableStepModeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Enabling step mode"))
        .doOnSuccess(_ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Step mode enabled"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to enable step mode"));
  }

  @Override
  public Mono<Void> disableStepMode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new DisableStepModeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Disabling step mode"))
        .doOnSuccess(_ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Step mode disabled"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to disable step mode"));
  }

  @Override
  public Mono<Void> stepNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new StepNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Stepping through node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node step command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to step node"));
  }

  @Override
  public Mono<String> restartWorkflow(final String executionId) {
    return awaitRestart(
        executionId,
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

  @Override
  public Mono<String> restartFromNode(final String executionId, final String fromNodeId) {
    return awaitRestart(
        executionId,
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

  private Mono<String> awaitRestart(
      final String executionId,
      final java.util.function.Function<String, ExecutionControlCommand> commandFactory,
      final java.util.function.Consumer<String> onSubscribeLogger,
      final java.util.function.Consumer<String> onSuccessLogger,
      final java.util.function.Consumer<Throwable> onErrorLogger) {
    return Mono.defer(
            () -> {
              findExecutionOrThrow(executionId);
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

  @Override
  public void completeRestartSuccess(final String newExecutionId) {
    final Sinks.One<String> sink = pendingRestarts.remove(newExecutionId);
    if (sink == null) {
      log.atDebug()
          .addKeyValue("newExecutionId", newExecutionId)
          .log("Restart completion arrived after caller already timed out");
      return;
    }
    sink.tryEmitValue(newExecutionId);
  }

  @Override
  public void completeRestartFailure(final String newExecutionId, final Throwable error) {
    final Sinks.One<String> sink = pendingRestarts.remove(newExecutionId);
    if (sink == null) {
      log.atDebug()
          .addKeyValue("newExecutionId", newExecutionId)
          .log("Restart failure arrived after caller already timed out");
      return;
    }
    sink.tryEmitError(error);
  }

  // --- Observability ---

  @Override
  public Flux<WorkflowProgress> watchExecution(
      final String executionId, final boolean includeHistory) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("includeHistory", includeHistory)
        .log("Starting to watch workflow execution");
    return taskTracker
        .getStatusStream(executionId, includeHistory)
        .doOnSubscribe(
            _ ->
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .log("Subscribed to execution status stream"))
        .doOnNext(
            progress ->
                log.atTrace()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("status", progress.status())
                    .addKeyValue("taskCount", progress.tasks().size())
                    .log("Received execution progress update"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .log("Stream error"))
        .doOnComplete(
            () -> log.atInfo().addKeyValue("executionId", executionId).log("Stream completed"));
  }

  @Override
  public Flux<String> watchLogs(final String sessionId, final String executionId) {
    log.atDebug()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("executionId", executionId)
        .log("Starting to watch execution logs");
    return taskTracker
        .getLogStream(executionId)
        .doOnSubscribe(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("executionId", executionId)
                    .log("Subscribed to log stream"))
        .doOnNext(
            logLine ->
                log.atTrace()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("executionId", executionId)
                    .log(logLine))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("executionId", executionId)
                    .log("Log stream error"));
  }

  @Override
  public WorkflowProgress getCurrentProgress(final String executionId) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .log("Fetching current execution progress");
    final WorkflowProgress progress = taskTracker.getProgressByExecutionId(executionId);
    if (progress != null) {
      log.atDebug()
          .addKeyValue("executionId", executionId)
          .addKeyValue("status", progress.status())
          .addKeyValue("taskCount", progress.tasks().size())
          .log("Current progress retrieved");
    } else {
      log.atWarn().addKeyValue("executionId", executionId).log("No progress found for execution");
    }
    return progress;
  }

  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(final String sessionId) {
    log.atDebug().addKeyValue("sessionId", sessionId).log("Fetching execution history");
    return taskTracker
        .getHistory(sessionId)
        .doOnNext(
            history ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("executionCount", history.size())
                    .log("Execution history retrieved"));
  }

  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(
      final String sessionId, final String workflowId) {
    log.atDebug()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("workflowId", workflowId)
        .log("Fetching workflow execution history");
    return taskTracker
        .getHistory(sessionId, workflowId)
        .doOnNext(
            history ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("executionCount", history.size())
                    .log("Workflow execution history retrieved"));
  }

  // --- State Queries ---

  @Override
  public Message<?> getLastHeartbeat(final String workflowId, final String nodeId) {
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Fetching last heartbeat");
    final Message<?> heartbeat = controlBusService.getLastHeartbeat(workflowId, nodeId);
    if (heartbeat != null) {
      log.atTrace()
          .addKeyValue("workflowId", workflowId)
          .addKeyValue("nodeId", nodeId)
          .log("Last heartbeat found");
    } else {
      log.atDebug()
          .addKeyValue("workflowId", workflowId)
          .addKeyValue("nodeId", nodeId)
          .log("No heartbeat found");
    }
    return heartbeat;
  }

  @Override
  public Message<?> getLastStatistics(final String workflowId, final String nodeId) {
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Fetching last statistics");
    final Message<?> statistics = controlBusService.getLastStatistics(workflowId, nodeId);
    if (statistics != null) {
      log.atTrace()
          .addKeyValue("workflowId", workflowId)
          .addKeyValue("nodeId", nodeId)
          .log("Last statistics found");
    } else {
      log.atDebug()
          .addKeyValue("workflowId", workflowId)
          .addKeyValue("nodeId", nodeId)
          .log("No statistics found");
    }
    return statistics;
  }

  @Override
  public List<String> getActiveNodes(final String workflowId) {
    log.atDebug().addKeyValue("workflowId", workflowId).log("Fetching active nodes for workflow");
    final List<String> activeNodes = controlBusService.getActiveNodes(workflowId);
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("activeNodeCount", activeNodes.size())
        .log("Active nodes retrieved");
    return activeNodes;
  }

  @Override
  public List<String> getActiveNodes() {
    log.atDebug().log("Fetching all active nodes");
    final List<String> allActiveNodes = controlBusService.getActiveNodes();
    log.atDebug()
        .addKeyValue("totalActiveNodes", allActiveNodes.size())
        .log("All active nodes retrieved");
    return allActiveNodes;
  }
}
