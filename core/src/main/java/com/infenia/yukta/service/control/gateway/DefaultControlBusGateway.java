// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.NodeHeartbeat;
import com.infenia.yukta.model.control.PendingApproval;
import com.infenia.yukta.model.control.SchedulerLimits;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default implementation of the {@link ControlBusGateway}.
 *
 * <p>Delegates low-level plugin management and message operations to {@link ControlBusService}, and
 * high-level control commands and observability operations to {@link TaskTrackerService}.
 */
@Slf4j
@Service
@Primary
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.GodClass",
  "PMD.ExcessivePublicCount"
})
public class DefaultControlBusGateway implements ControlBusGateway, RestartCompletionSink {

  /** What the command-sending parts share. */
  private final ControlDispatch dispatch;

  /** Commands for one node of a live run. */
  private final NodeCommands nodes;

  /** Restarts of runs. */
  private final RestartCoordinator restarts;

  /** Read-only views of executions and node state. */
  private final ExecutionQueries queries;

  /** The control bus service for low-level plugin and message operations. */
  private final ControlBusService controlBusService;

  /** The task tracker service for tracking execution tasks. */
  private final TaskTrackerService taskTracker;

  /** The execution control registry for managing active executions. */
  private final ExecutionControlRegistry executionControlRegistry;

  /** Finished executions that can still be restarted. */
  private final ExecutionArchive executionArchive;

  /** Limits and queues concurrent executions. */
  private final ExecutionScheduler executionScheduler;

  /** Records changes that do not go through a run's command queue. */
  private final ExecutionLogStore executionLog;

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /** Starts executions. */
  private final WorkflowStarter starter;

  /** Asks the supervisors about commands that do not pass through the dispatcher. */
  private ControlReviewer reviewer;

  /**
   * Creates the gateway.
   *
   * @param controlBusService delivers the control commands
   * @param taskTracker tracks runs
   * @param executionControlRegistry finds live runs
   * @param executionArchive finished runs that can be restarted
   * @param orchestrator prepares and runs workflows
   * @param workflowDefinitionStore the stored definitions
   * @param preparedWorkflowCache prepared workflows
   * @param executionScheduler limits and queues executions
   * @param startIdempotency makes starts safe to retry
   * @param executionLog records changes that belong to no run's command queue
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public DefaultControlBusGateway(
      final ControlBusService controlBusService,
      final TaskTrackerService taskTracker,
      final ExecutionControlRegistry executionControlRegistry,
      final ExecutionArchive executionArchive,
      final WorkflowOrchestrator orchestrator,
      final WorkflowDefinitionStore workflowDefinitionStore,
      final PreparedWorkflowCache preparedWorkflowCache,
      final ExecutionScheduler executionScheduler,
      final StartIdempotency startIdempotency,
      final ExecutionLogStore executionLog) {
    this.controlBusService = controlBusService;
    this.taskTracker = taskTracker;
    this.executionControlRegistry = executionControlRegistry;
    this.executionArchive = executionArchive;
    this.executionScheduler = executionScheduler;
    this.executionLog = executionLog;
    this.dispatch = new ControlDispatch(this, executionControlRegistry);
    this.nodes = new NodeCommands(this, executionControlRegistry);
    this.restarts =
        new RestartCoordinator(this, executionControlRegistry, executionArchive, startIdempotency);
    this.queries = new ExecutionQueries(taskTracker, controlBusService);
    this.starter =
        new WorkflowStarter(
            workflowDefinitionStore,
            preparedWorkflowCache,
            orchestrator,
            executionScheduler,
            startIdempotency);
  }

  // --- Plugin & Message Management ---

  @Override
  public <T> Mono<Void> emit(final Message<T> signal) {
    return Mono.defer(
            () -> {
              log.atTrace()
                  .addKeyValue("sourceNodeId", signal.sourceNodeId())
                  .addKeyValue("priority", signal.priority())
                  .log("Emitting message to control bus");
              return controlBusService.emit(signal);
            })
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("sourceNodeId", signal.sourceNodeId())
                    .log("Message emitted successfully"))
        .doOnError(err -> logEmitFailure(signal.sourceNodeId(), err));
  }

  /** A refusal by a supervisor is an intended outcome: INFO without a trace, not ERROR. */
  private static void logEmitFailure(final String sourceNodeId, final Throwable err) {
    if (err instanceof ControlRejectedException) {
      log.atInfo()
          .addKeyValue("sourceNodeId", sourceNodeId)
          .addKeyValue("reason", err.getMessage())
          .log("Control command refused");
    } else {
      log.atError()
          .setCause(err)
          .addKeyValue("sourceNodeId", sourceNodeId)
          .log("Failed to emit message");
    }
  }

  @Override
  public void registerPlugin(final String workflowId, final String nodeId, final Plugin plugin) {
    log.atDebug()
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
    log.atDebug()
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
    return carriesSignal(command)
        ? Mono.error(
            new ControlRejectedException("Approvals and signals go through the signal command"))
        : deliver(workflowId, nodeId, command);
  }

  /**
   * A signal reaches a waiting node here without reviewer or audit; the signal command has both.
   */
  private static boolean carriesSignal(final Message<?> command) {
    return command.payload() instanceof Map<?, ?> body
        && (body.containsKey("decision") || body.containsKey("executionId"));
  }

  private Mono<Message<?>> deliver(
      final String workflowId, final String nodeId, final Message<?> command) {
    return Mono.defer(
            () -> {
              log.atDebug()
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
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflowId", workflowDefinition.workflowId())
                  .addKeyValue("nodeCount", workflowDefinition.nodes().size())
                  .addKeyValue("edgeCount", workflowDefinition.edges().size())
                  .log("Compiling and caching workflow");
              return controlBusService.compileAndCacheWorkflow(sessionId, workflowDefinition);
            })
        .then(Mono.defer(() -> starter.dropCacheIfStale(sessionId, workflowDefinition)))
        .doOnSuccess(
            _ ->
                log.atDebug()
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
  public Mono<String> startWorkflow(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    return starter.start(sessionId, workflowId, payload);
  }

  @Override
  public Mono<String> startWorkflow(
      final String sessionId,
      final String workflowId,
      final Map<String, Object> payload,
      final String idempotencyKey) {
    return starter.start(sessionId, workflowId, payload, idempotencyKey);
  }

  @Override
  public <T extends ExecutionControlCommand> Mono<Void> executeCommand(final Message<T> command) {
    final var commandType = command.payload().getClass().getSimpleName();
    log.atDebug().addKeyValue("commandType", commandType).log("Executing control command");
    return emit(command);
  }

  @Override
  public Mono<Void> pauseWorkflow(final String executionId) {
    return Mono.fromSupplier(() -> dispatch.findExecutionOrThrow(executionId))
        .flatMap(
            control ->
                executeCommand(
                    ControlDispatch.buildCommand(
                        new PauseWorkflowCommand(executionId),
                        ControlDispatch.CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ -> log.atDebug().addKeyValue("executionId", executionId).log("Pausing workflow"))
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
    return Mono.fromSupplier(() -> dispatch.findExecutionOrThrow(executionId))
        .flatMap(
            control ->
                executeCommand(
                    ControlDispatch.buildCommand(
                        new ResumeWorkflowCommand(executionId),
                        ControlDispatch.CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ -> log.atDebug().addKeyValue("executionId", executionId).log("Resuming workflow"))
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
  public Mono<List<String>> stopWorkflow(
      final String sessionId, final String workflowId, final String reason, final StopMode mode) {
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
                                    ControlDispatch.buildCommand(
                                        new StopWorkflowCommand(
                                            control.executionId(), reason, mode),
                                        ControlDispatch.CONTROL_COMMAND_PRIORITY + 20))
                                .thenReturn(control.executionId())))
        .collectList()
        .doOnSubscribe(
            _ ->
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("reason", reason)
                    .addKeyValue("mode", mode)
                    .log("Stopping all workflow executions"))
        .doOnSuccess(
            stoppedIds ->
                log.atDebug()
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
  public Mono<String> stopExecution(
      final String executionId, final String reason, final StopMode mode) {
    return Mono.defer(
            () ->
                executionControlRegistry
                    .findByExecutionId(executionId)
                    .map(
                        control ->
                            executeCommand(
                                    ControlDispatch.buildCommand(
                                        new StopWorkflowCommand(
                                            control.executionId(), reason, mode),
                                        ControlDispatch.CONTROL_COMMAND_PRIORITY + 20))
                                .thenReturn(control.executionId()))
                    .orElseGet(() -> queries.finishOrphan(executionId)))
        .doOnSubscribe(
            _ ->
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("reason", reason)
                    .addKeyValue("mode", mode)
                    .log("Stopping specific execution"))
        .doOnSuccess(
            stoppedId ->
                log.atDebug()
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
  public Mono<String> retryNode(final String executionId, final String nodeId) {
    return Mono.defer(
        () -> {
          final WorkflowProgress progress = taskTracker.getProgressByExecutionId(executionId);
          if (progress == null) {
            return Mono.error(new NotFoundException("Execution not found: " + executionId));
          }
          final String status =
              progress.tasks().stream()
                  .filter(task -> task.nodeId().equals(nodeId))
                  .map(TaskProgress::status)
                  .findFirst()
                  .orElseThrow(() -> new NotFoundException("Node not found: " + nodeId));
          if (!TaskStatus.FAILURE.name().equals(status)
              && !TaskStatus.TIMED_OUT.name().equals(status)) {
            return Mono.error(
                new IllegalArgumentException(
                    "Node "
                        + nodeId
                        + " did not fail in "
                        + executionId
                        + " (status "
                        + status
                        + ")"));
          }
          return restartFromNode(executionId, nodeId);
        });
  }

  @Override
  public Mono<Void> recordAdminAction(
      final String command,
      final String target,
      final boolean applied,
      @Nullable final String reason) {
    return Mono.deferContextual(
        context -> {
          final String actor = context.getOrDefault(ControlActor.CONTEXT_KEY, null);
          ControlAudit.record(
              executionLog,
              auditSink,
              new AuditRecord(applied, target, command, actor, reason, Instant.now()));
          return Mono.empty();
        });
  }

  /**
   * Cancels the runs that are still going when the application shuts down. The scheduler has
   * already waited for them (its shutdown timeout); what is left would otherwise be abandoned
   * running.
   */
  @PreDestroy
  public void cancelRunningExecutions() {
    starter.cancelAll();
  }

  @Override
  public Mono<SchedulerLimits> limits() {
    return Mono.fromSupplier(executionScheduler::limits);
  }

  @Override
  public Mono<Void> changeLimits(final int maxConcurrent, final int maxPerWorkflow) {
    final String name = "SetLimits(" + maxConcurrent + ", " + maxPerWorkflow + ")";
    return reviewedAndLogged(
        new ExecutionControlCommand.SetLimitsCommand(maxConcurrent, maxPerWorkflow),
        name,
        () -> {
          executionScheduler.setLimits(maxConcurrent, maxPerWorkflow);
          executionLog.append(ExecutionLogEvent.limitsChanged(maxConcurrent, maxPerWorkflow));
          log.atInfo()
              .addKeyValue("maxConcurrent", maxConcurrent)
              .addKeyValue("maxPerWorkflow", maxPerWorkflow)
              .log("Execution limits changed");
        });
  }

  @Override
  public Mono<Void> changePriority(final String executionId, final int priority) {
    return reviewedAndLogged(
        new ExecutionControlCommand.SetPriorityCommand(executionId, priority),
        "SetPriority(" + priority + ")",
        () -> {
          if (!executionScheduler.changePriority(executionId, priority)) {
            throw new IllegalArgumentException(
                "Execution " + executionId + " is not waiting for a slot");
          }
        });
  }

  /** The review request for a command, with the session and workflow of its live run if any. */
  private ControlRequest request(final ExecutionControlCommand command, final String actor) {
    return executionControlRegistry
        .findByExecutionId(command.executionId())
        .map(run -> new ControlRequest(command, actor, run.sessionId(), run.workflowId()))
        .orElseGet(() -> new ControlRequest(command, actor, null, null));
  }

  /**
   * Asks the supervisors, applies a command that has no run queue and records the outcome in the
   * execution log with the actor, like the commands that go through the dispatcher.
   */
  private Mono<Void> reviewedAndLogged(
      final ExecutionControlCommand command, final String name, final Runnable apply) {
    return Mono.deferContextual(
        context -> {
          final String actor = context.getOrDefault(ControlActor.CONTEXT_KEY, null);
          final Mono<Void> review =
              reviewer == null ? Mono.empty() : reviewer.review(request(command, actor));
          return review
              .then(Mono.<Void>fromRunnable(apply))
              .doOnSuccess(
                  _ ->
                      ControlAudit.record(
                          executionLog,
                          auditSink,
                          new AuditRecord(
                              true, command.executionId(), name, actor, null, Instant.now())))
              .doOnError(
                  error ->
                      ControlAudit.record(
                          executionLog,
                          auditSink,
                          new AuditRecord(
                              false,
                              command.executionId(),
                              name,
                              actor,
                              error.getMessage(),
                              Instant.now())));
        });
  }

  /**
   * Passes every audit record on to a sink, besides the execution log.
   *
   * @param sink the sink, if the application has one
   */
  @Autowired(required = false)
  public void setAuditSink(final AuditSink sink) {
    this.auditSink = sink;
  }

  /**
   * Lets supervisors refuse limit and priority changes; without one they are applied unreviewed.
   *
   * @param controlReviewer the reviewer, if supervisors are enabled
   */
  @Autowired(required = false)
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects the shared service, which is meant to be held")
  public void setReviewer(final ControlReviewer controlReviewer) {
    this.reviewer = controlReviewer;
  }

  @Override
  public Mono<Void> pauseNode(final String executionId, final String nodeId) {
    return nodes.pauseNode(executionId, nodeId);
  }

  @Override
  public Mono<Void> resumeNode(final String executionId, final String nodeId) {
    return nodes.resumeNode(executionId, nodeId);
  }

  @Override
  public Mono<Void> stopNode(
      final String executionId, final String nodeId, final boolean immediate, final String reason) {
    return nodes.stopNode(executionId, nodeId, immediate, reason);
  }

  @Override
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<Void> addBreakpoint(
      final String executionId, final String nodeId, final String field, final String equalsValue) {
    return nodes.addBreakpoint(executionId, nodeId, field, equalsValue);
  }

  @Override
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<Void> signalNode(
      final String executionId,
      final String nodeId,
      final String name,
      final String decision,
      final String data) {
    return nodes.signalNode(executionId, nodeId, name, decision, data);
  }

  @Override
  public Mono<Void> clearBreakpoint(final String executionId, final String nodeId) {
    return nodes.clearBreakpoint(executionId, nodeId);
  }

  @Override
  public Mono<Void> skipNode(final String executionId, final String nodeId, final boolean skip) {
    return nodes.skipNode(executionId, nodeId, skip);
  }

  @Override
  public Mono<Void> enableStepMode(final String executionId, final String nodeId) {
    return nodes.enableStepMode(executionId, nodeId);
  }

  @Override
  public Mono<Void> disableStepMode(final String executionId, final String nodeId) {
    return nodes.disableStepMode(executionId, nodeId);
  }

  @Override
  public Mono<Void> stepNode(final String executionId, final String nodeId) {
    return nodes.stepNode(executionId, nodeId);
  }

  @Override
  public Mono<String> restartWorkflow(final String executionId) {
    return restarts.restartWorkflow(executionId);
  }

  @Override
  public Mono<String> restartFromNode(final String executionId, final String fromNodeId) {
    return restarts.restartFromNode(executionId, fromNodeId);
  }

  @Override
  public Flux<WorkflowProgress> watchExecution(
      final String executionId, final boolean includeHistory) {
    return queries.watchExecution(executionId, includeHistory);
  }

  @Override
  public Flux<PendingApproval> pendingApprovals(final String sessionId) {
    return queries.pendingApprovals(sessionId);
  }

  @Override
  public Flux<String> watchLogs(final String sessionId, final String executionId) {
    return queries.watchLogs(sessionId, executionId);
  }

  @Override
  public WorkflowProgress getProgress(final String sessionId, final String executionId) {
    return queries.getProgress(sessionId, executionId);
  }

  @Override
  public WorkflowProgress getCurrentProgress(final String executionId) {
    return queries.getCurrentProgress(executionId);
  }

  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(final String sessionId) {
    return queries.getHistory(sessionId);
  }

  @Override
  public Mono<List<WorkflowExecutionSummary>> getHistory(
      final String sessionId, final String workflowId) {
    return queries.getHistory(sessionId, workflowId);
  }

  @Override
  public Message<?> getLastHeartbeat(final String workflowId, final String nodeId) {
    return queries.getLastHeartbeat(workflowId, nodeId);
  }

  @Override
  public Message<?> getLastStatistics(final String workflowId, final String nodeId) {
    return queries.getLastStatistics(workflowId, nodeId);
  }

  @Override
  public List<NodeHeartbeat> getHeartbeats() {
    return controlBusService.getHeartbeats();
  }

  @Override
  public List<String> getActiveNodes(final String workflowId) {
    return queries.getActiveNodes(workflowId);
  }

  @Override
  public List<String> getActiveNodes() {
    return queries.getActiveNodes();
  }

  @Override
  public void completeRestartSuccess(final String newExecutionId) {
    restarts.completeRestartSuccess(newExecutionId);
  }

  @Override
  public void completeRestartFailure(final String newExecutionId, final Throwable error) {
    restarts.completeRestartFailure(newExecutionId, error);
  }
}
