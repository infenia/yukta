// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.archive.ArchivedExecution;
import com.infenia.yukta.service.archive.DefinitionVersion;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.QueueTimeoutException;
import com.infenia.yukta.service.control.factory.ExecutionControlFactory;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.compiler.ExecutionTimeoutException;
import com.infenia.yukta.service.orchestrator.compiler.WorkflowCompiler;
import com.infenia.yukta.service.orchestrator.preparator.WorkflowPreparator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/** Orchestrator for executing reactive workflow DAGs. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.UseObjectForClearerAPI"
})
public class WorkflowOrchestrator {

  /** Log key for session ID. */
  private static final String LOG_KEY_SESSION_ID = "sessionId";

  /** Log key for workflow ID. */
  private static final String LOG_KEY_WORKFLOW_ID = "workflowId";

  /** Log key for execution ID. */
  private static final String LOG_KEY_EXECUTION_ID = "executionId";

  /** Log key for node count. */
  private static final String LOG_KEY_NODE_COUNT = "nodeCount";

  /** The task tracker service for tracking workflow execution. */
  private final TaskTrackerService tracker;

  /** The execution control registry for managing workflow execution control. */
  private final ExecutionControlStore executionControlStore;

  /** The execution control factory for creating execution control instances. */
  private final ExecutionControlFactory executionControlFactory;

  /** The node checkpoint store for persisting node state. */
  private final NodeCheckpointStore checkpointStore;

  /** Keeps recent executions restartable. */
  private final ExecutionArchive executionArchive;

  /** Durable record of run starts. */
  private final ExecutionLogStore executionLog;

  /** Limits and queues concurrent executions. */
  private final ExecutionScheduler scheduler;

  /** The workflow compiler for compiling workflow definitions. */
  private final WorkflowCompiler compiler;

  /** The workflow preparator for preparing workflows for execution. */
  private final WorkflowPreparator preparator;

  /** Ends the execution's result stream when the run ends. */
  private final ExecutionResultService resultService;

  /** Evicted from the archive while still running: their checkpoints go when they end. */
  private final Cache<String, Boolean> clearWhenDone =
      Caffeine.newBuilder()
          .maximumSize(10_000)
          // a run whose cleanup never came is cleared when pushed out, so the entry cannot pile up
          .removalListener(
              (String id, Boolean _, RemovalCause cause) -> onPendingRemoved(id, cause))
          .build();

  /**
   * Prepares a workflow for execution.
   *
   * @param def the workflow definition (includes workflowId)
   * @return a Mono containing the prepared workflow
   */
  public Mono<PreparedWorkflow> prepareWorkflow(@NotNull @Valid final WorkflowDefinition def) {
    return preparator
        .prepareWorkflow(def)
        .doOnSuccess(
            prepared ->
                log.atDebug()
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, def.workflowId())
                    .addKeyValue(
                        "nodeCount", prepared != null ? prepared.topologicalOrder().size() : 0)
                    .log("Successfully prepared workflow for execution"))
        .doOnError(
            e ->
                log.atError()
                    .setCause(e)
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, def.workflowId())
                    .log("Failed to prepare workflow"));
  }

  /**
   * Executes a workflow.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @param executionId the execution ID
   * @param prepared the prepared workflow
   * @param payload the initial payload
   * @return a Mono that completes when the workflow execution is finished
   */
  public Mono<Void> execute(
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      @NotBlank final String executionId,
      @NotNull @Valid final PreparedWorkflow prepared,
      final Map<String, Object> payload) {
    return execute(sessionId, workflowId, executionId, prepared, payload, null);
  }

  /**
   * Executes a workflow with a slot that the caller has already asked the scheduler for, so a
   * refused start is known before the execution id is handed out. The execution must be subscribed
   * to: the slot is given back when it ends, or when it is cancelled.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @param executionId the execution ID
   * @param prepared the prepared workflow
   * @param payload the initial payload
   * @param admission the slot asked for, or null to ask when the run starts
   * @return a Mono that completes when the workflow execution is finished
   */
  public Mono<Void> execute(
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      @NotBlank final String executionId,
      @NotNull @Valid final PreparedWorkflow prepared,
      final Map<String, Object> payload,
      final ExecutionScheduler.@Nullable Admission admission) {

    // Registering, archiving and logging the start happen on subscription: a Mono that is never
    // subscribed leaves no registered control and no checkpoints to clear behind it.
    return releasing(
        Mono.defer(
            () -> {
              log.atInfo()
                  .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                  .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                  .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                  .addKeyValue(LOG_KEY_NODE_COUNT, prepared.topologicalOrder().size())
                  .log("Starting workflow execution");

              final ExecutionControl control =
                  executionControlFactory.create(
                      sessionId, workflowId, executionId, prepared, payload);
              executionControlStore.save(control);
              log.atDebug()
                  .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                  .log("Registered execution control");

              final List<String> nodeIds =
                  prepared.topologicalOrder().stream().map(WorkflowNode::nodeId).toList();

              // Subscribed together: tracking starts on this thread while the archive I/O runs
              // elsewhere.
              return Mono.when(
                  archive(executionId, sessionId, workflowId, prepared, payload),
                  runTracked(
                      control,
                      sessionId,
                      workflowId,
                      executionId,
                      nodeIds,
                      ExecutionScheduler.priorityOf(payload),
                      admission,
                      prepared.template().instantiate(executionId, payload),
                      new Origin("Workflow execution", null)));
            }),
        admission);
  }

  /**
   * Executes a workflow starting from a specific node, replaying checkpoint messages from its
   * direct parents.
   *
   * <p>Nodes that appear before {@code restartNodeId} in topological order are replaced with bypass
   * assemblers. Direct parents of the restart node emit their stored checkpoint message; all other
   * predecessors emit an empty stream. The restart node and its successors run normally.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param previousExecutionId the execution whose checkpoints to replay
   * @param newExecutionId the new execution identifier
   * @param prepared the prepared workflow
   * @param restartNodeId the node from which to resume execution
   * @param boundaryOutputs recorded output of each node that feeds the re-run part, by node id
   * @return a Mono that completes when the restarted execution finishes
   */
  public Mono<Void> restartFromNode(
      final String sessionId,
      final String workflowId,
      final String previousExecutionId,
      final String newExecutionId,
      final PreparedWorkflow prepared,
      final String restartNodeId,
      final Map<String, List<Message<?>>> boundaryOutputs) {
    return restartFromNode(
        sessionId,
        workflowId,
        previousExecutionId,
        newExecutionId,
        prepared,
        restartNodeId,
        boundaryOutputs,
        null);
  }

  /**
   * As {@link #restartFromNode(String, String, String, String, PreparedWorkflow, String, Map)},
   * with the slot already taken by the caller (given back if the restart cannot begin).
   *
   * @param admission the slot taken in advance, or null to ask for one when the run starts
   */
  public Mono<Void> restartFromNode(
      final String sessionId,
      final String workflowId,
      final String previousExecutionId,
      final String newExecutionId,
      final PreparedWorkflow prepared,
      final String restartNodeId,
      final Map<String, List<Message<?>>> boundaryOutputs,
      final ExecutionScheduler.@Nullable Admission admission) {

    final RestartPlan plan;
    try {
      plan = RestartPlan.compute(prepared, restartNodeId);
    } catch (final IllegalArgumentException e) {
      giveBack(admission);
      return Mono.error(e);
    }
    return rerun(
        sessionId,
        workflowId,
        previousExecutionId,
        newExecutionId,
        prepared,
        plan,
        executionArchive
            .find(previousExecutionId)
            .<Map<String, Object>>map(ArchivedExecution::payload)
            .orElse(Map.of()),
        boundaryOutputs,
        "restartNodeId=" + restartNodeId,
        admission);
  }

  /**
   * Runs the part of a workflow that a plan names as a new execution, replaying the recorded output
   * of the nodes that feed it. Used by restart from a node and by resuming an aborted run.
   *
   * @param payload trigger payload, for triggers inside the re-run part
   * @param origin what started this, for the log
   * @param admission the slot taken in advance (given back if the run cannot begin), or null to ask
   *     for one when the run starts
   */
  @SuppressWarnings({"PMD.ExcessiveParameterList", "PMD.AvoidCatchingGenericException"})
  public Mono<Void> rerun(
      final String sessionId,
      final String workflowId,
      final String previousExecutionId,
      final String newExecutionId,
      final PreparedWorkflow prepared,
      final RestartPlan plan,
      final Map<String, Object> payload,
      final Map<String, List<Message<?>>> boundaryOutputs,
      final String origin,
      final ExecutionScheduler.@Nullable Admission admission) {

    log.atInfo()
        .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
        .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
        .addKeyValue("previousExecutionId", previousExecutionId)
        .addKeyValue(LOG_KEY_EXECUTION_ID, newExecutionId)
        .addKeyValue("origin", origin)
        .addKeyValue("rerunNodes", plan.rerun().size())
        .addKeyValue("replayedNodes", plan.boundary())
        .log("Starting workflow restart from node");

    final List<WorkflowNode> topologicalOrder = prepared.topologicalOrder();
    final int nodeCount = topologicalOrder.size();
    // Nodes outside the re-run set do not run: the ones feeding it replay what they recorded, the
    // rest stay silent, so independent branches are not executed again.
    final NodeAssembler[] assemblers;
    try {
      assemblers =
          compiler.compileRerunAssemblers(
              prepared.edges(),
              prepared.parentsList(),
              prepared.pluginCache(),
              topologicalOrder,
              plan,
              boundaryOutputs,
              previousExecutionId);
    } catch (final RuntimeException e) {
      giveBack(admission);
      return Mono.error(e);
    }

    final List<String> nodeIds = topologicalOrder.stream().map(WorkflowNode::nodeId).toList();

    // Registering and archiving happen on subscription, so an unsubscribed Mono leaks nothing.
    return releasing(
        Mono.defer(
            () -> {
              final ExecutionControl control =
                  executionControlFactory.create(
                      sessionId, workflowId, newExecutionId, prepared, payload);
              executionControlStore.save(control);
              log.atDebug()
                  .addKeyValue(LOG_KEY_EXECUTION_ID, newExecutionId)
                  .log("Registered execution control for restarted workflow");

              // Record the replayed input under the new execution, so it can be restarted in turn.
              final Mono<Void> recordReplayed =
                  Flux.fromIterable(boundaryOutputs.entrySet())
                      .concatMap(
                          entry ->
                              Flux.fromIterable(entry.getValue())
                                  .concatMap(
                                      message ->
                                          checkpointStore.save(
                                              newExecutionId, entry.getKey(), message))
                                  // The replayed output is complete, so a resume of this run need
                                  // not
                                  // run the node again.
                                  .then(
                                      Mono.defer(
                                              () ->
                                                  checkpointStore.markComplete(
                                                      newExecutionId, entry.getKey()))
                                          .onErrorResume(_ -> Mono.empty())))
                      .then();

              return Mono.when(
                  archive(newExecutionId, sessionId, workflowId, prepared, payload),
                  runTracked(
                      control,
                      sessionId,
                      workflowId,
                      newExecutionId,
                      nodeIds,
                      ExecutionScheduler.priorityOf(payload),
                      admission,
                      recordReplayed.then(
                          Mono.defer(
                              () ->
                                  compiler.executeTemplate(
                                      newExecutionId,
                                      payload,
                                      nodeCount,
                                      assemblers,
                                      sessionId,
                                      workflowId,
                                      nodeIds))),
                      new Origin("RestartFromNode execution", previousExecutionId)));
            }),
        admission);
  }

  /** Waits for an execution slot, showing the run as QUEUED while it has to wait. */
  private Mono<ExecutionScheduler.Permit> awaitSlot(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final int priority,
      final ExecutionScheduler.@Nullable Admission reserved) {
    return Mono.defer(
        () -> {
          final ExecutionScheduler.Admission admission =
              reserved != null
                  ? reserved
                  : scheduler.acquire(sessionId, workflowId, priority, executionId);
          // A refused admission is always immediate, so its permit errors without a wait.
          if (admission.immediate()) {
            return admission.permit();
          }
          tracker.emitWorkflowStatusEvent(executionId, TaskStatus.QUEUED);
          return admission
              .permit()
              .doOnNext(_ -> tracker.emitWorkflowStatusEvent(executionId, TaskStatus.RUNNING));
        });
  }

  /**
   * Remembers an execution so it can be restarted, and frees what older ones recorded. The log
   * append and the checkpoint clearing are file or database I/O, so they run on {@code
   * boundedElastic}, not on the subscriber's (event loop) thread.
   */
  private Mono<Void> archive(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final PreparedWorkflow prepared,
      final Map<String, Object> payload) {
    return Mono.<Void>fromRunnable(
            () -> archiveBlocking(executionId, sessionId, workflowId, prepared, payload))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void archiveBlocking(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final PreparedWorkflow prepared,
      final Map<String, Object> payload) {
    final String version = DefinitionVersion.of(prepared);
    executionLog.append(
        ExecutionLogEvent.started(executionId, sessionId, workflowId, version, payload));
    tracker.setInitiator(executionId, ExecutionLogEvent.initiatorOf(payload));
    executionArchive
        .put(new ArchivedExecution(executionId, sessionId, workflowId, prepared, version, payload))
        .forEach(this::releaseCheckpoints);
  }

  /** Drops the recorded outputs of an evicted run, or at its end if it is still running. */
  private void releaseCheckpoints(final String executionId) {
    clearWhenDone.put(executionId, Boolean.TRUE);
    if (executionControlStore.findByExecutionId(executionId).isEmpty()) {
      clearIfPending(executionId);
    }
  }

  /** A slot asked for in advance is released when the run fails before it could use it. */
  private static void giveBack(final ExecutionScheduler.@Nullable Admission reserved) {
    if (reserved != null) {
      reserved.giveBack();
    }
  }

  /** Gives the reserved slot back if the run fails or is cancelled at any point, once only. */
  private static Mono<Void> releasing(
      final Mono<Void> run, final ExecutionScheduler.@Nullable Admission reserved) {
    return run.doOnError(_ -> giveBack(reserved)).doOnCancel(() -> giveBack(reserved));
  }

  /**
   * Clears the checkpoints of a run whose cleanup was still pending when the cache pushed it out.
   * Removals that are not evictions (the cleanup ran) leave them alone.
   *
   * @param executionId the run
   * @param cause why the entry left the cache
   */
  /* default */ void onPendingRemoved(final String executionId, final RemovalCause cause) {
    if (cause.wasEvicted()) {
      clearCheckpoints(executionId);
    }
  }

  private void clearCheckpoints(final String executionId) {
    checkpointStore.clear(executionId);
  }

  private void clearIfPending(final String executionId) {
    if (clearWhenDone.asMap().remove(executionId) != null) {
      checkpointStore.clear(executionId);
    }
  }

  /**
   * Runs a workflow body under the shared lifecycle: start tracking, race the body against the
   * safe-stop signal, record the final status, and release per-execution resources.
   *
   * @param control the registered control handle of this execution
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param executionId the execution identifier
   * @param nodeIds the node identifiers to track
   * @param body the work to run once tracking has started
   * @param origin what started the run: its description in log messages and the run it restarts
   * @return a Mono completing when the execution finished, was stopped, or failed
   */
  private Mono<Void> runTracked(
      final ExecutionControl control,
      final String sessionId,
      final String workflowId,
      final String executionId,
      final List<String> nodeIds,
      final int priority,
      final ExecutionScheduler.@Nullable Admission reserved,
      final Mono<Void> body,
      final Origin origin) {
    // The body runs once the scheduler grants a slot, which is released however the run ends.
    final Mono<Void> execution =
        tracker
            .startWorkflow(executionId, sessionId, workflowId, nodeIds)
            .doOnSuccess(
                _ -> {
                  // a sub-workflow names its parent in its start payload (see SubWorkflowRunner)
                  final Object parent = control.payload().get(SubWorkflowGateway.PARENT_KEY);
                  tracker.recordLineage(
                      executionId, parent == null ? null : parent.toString(), origin.restartOf());
                  if (control.prepared() != null) {
                    tracker.recordDefinitionVersion(
                        executionId, DefinitionVersion.of(control.prepared()));
                  }
                })
            .then(
                Mono.usingWhen(
                    awaitSlot(sessionId, workflowId, executionId, priority, reserved),
                    // Tracking above starts on the caller's thread, so a started run can be read
                    // at once; only the work itself moves off it.
                    _ -> body.subscribeOn(Schedulers.boundedElastic()),
                    permit -> Mono.fromRunnable(permit::release)));

    // Cleanup must precede the terminal signal: a caller that sees the run end must find it
    // already unregistered. Terminate and cancel are exclusive, but a guard keeps it once-only.
    final AtomicBoolean cleaned = new AtomicBoolean();
    final Runnable cleanup =
        () -> {
          if (cleaned.compareAndSet(false, true)) {
            executionControlStore.remove(executionId);
            clearIfPending(executionId);
            // Ends the run: lets a pending drain deadline timer go instead of living on.
            control.safeStopSink().tryEmitEmpty();
            log.atDebug()
                .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                .log("Cleaned up resources");
          }
        };

    return Mono.firstWithSignal(
            // A run that ended because it was stopped (for example after a drain) is STOPPED.
            execution.then(
                Mono.fromSupplier(
                    () -> control.stopRequested().get() ? TaskStatus.STOPPED : TaskStatus.SUCCESS)),
            control.safeStopSink().asMono().thenReturn(TaskStatus.STOPPED))
        .flatMap(
            status ->
                tracker
                    .finishWorkflow(executionId, status)
                    .doFinally(_ -> resultService.complete(executionId, status.name())))
        .doOnSuccess(
            _ ->
                log.atInfo()
                    .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                    .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                    .log("{} completed", origin.label()))
        .onErrorResume(
            e ->
                tracker
                    .finishWorkflow(executionId, failureStatus(e))
                    .doFinally(_ -> resultService.complete(executionId, failureStatus(e).name()))
                    .doOnSuccess(
                        _ ->
                            log.atError()
                                .setCause(e)
                                .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                                .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                                .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                                .log("{} failed", origin.label()))
                    .then(Mono.error(e)))
        .doOnCancel(
            () ->
                // A caller that goes away ends the run too; if a stop already did, the tracker
                // ignores this second end.
                tracker
                    .finishWorkflow(executionId, TaskStatus.CANCELLED)
                    .doFinally(
                        _ -> resultService.complete(executionId, TaskStatus.CANCELLED.name()))
                    .onErrorComplete()
                    .subscribe())
        .doOnCancel(cleanup)
        .doOnTerminate(cleanup)
        .contextWrite(
            Context.of(
                ExecutionContextBuilder.CTX_SESSION_ID, sessionId,
                ExecutionContextBuilder.CTX_WORKFLOW_ID, workflowId,
                ExecutionContextBuilder.CTX_EXECUTION_ID, executionId));
  }

  /**
   * What started a run.
   *
   * @param label description used in log messages
   * @param restartOf the run it restarts, or null
   */
  private record Origin(String label, @Nullable String restartOf) {}

  private static TaskStatus failureStatus(final Throwable error) {
    return error instanceof QueueTimeoutException || error instanceof ExecutionTimeoutException
        ? TaskStatus.TIMED_OUT
        : TaskStatus.FAILURE;
  }
}
