// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.PendingApproval;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.util.SignalWaits;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read-only views of executions, history and node state. */
@Slf4j
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
final class ExecutionQueries {

  /** The task tracker service for tracking execution tasks. */
  private final TaskTrackerService taskTracker;

  /** The control bus service for low-level plugin and message operations. */
  private final ControlBusService controlBusService;

  /* default */ ExecutionQueries(
      final TaskTrackerService taskTracker, final ControlBusService controlBusService) {
    this.taskTracker = taskTracker;
    this.controlBusService = controlBusService;
  }

  /* default */ Flux<WorkflowProgress> watchExecution(
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

  /* default */ Flux<String> watchLogs(final String sessionId, final String executionId) {
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

  /* default */ WorkflowProgress getProgress(final String sessionId, final String executionId) {
    return taskTracker.getProgress(sessionId, executionId);
  }

  /* default */ Flux<PendingApproval> pendingApprovals(final String sessionId) {
    return Flux.fromIterable(SignalWaits.shared().pending())
        .concatMap(
            wait -> {
              final WorkflowProgress progress =
                  taskTracker.getProgressByExecutionId(wait.executionId());
              return progress != null
                      && (sessionId == null || sessionId.equals(progress.sessionId()))
                  ? Mono.just(
                      new PendingApproval(
                          progress.sessionId(),
                          wait.executionId(),
                          progress.workflowId(),
                          wait.nodeId(),
                          wait.name(),
                          wait.since()))
                  : Mono.<PendingApproval>empty();
            });
  }

  /* default */ WorkflowProgress getCurrentProgress(final String executionId) {
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

  /* default */ Mono<List<WorkflowExecutionSummary>> getHistory(final String sessionId) {
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

  /* default */ Mono<List<WorkflowExecutionSummary>> getHistory(
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

  /* default */ Message<?> getLastHeartbeat(
      final String workflowId, final String executionId, final String nodeId) {
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Fetching last heartbeat");
    final Message<?> heartbeat =
        controlBusService.getLastHeartbeat(workflowId, executionId, nodeId);
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

  /* default */ Message<?> getLastStatistics(
      final String workflowId, final String executionId, final String nodeId) {
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("nodeId", nodeId)
        .log("Fetching last statistics");
    final Message<?> statistics =
        controlBusService.getLastStatistics(workflowId, executionId, nodeId);
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

  /* default */ List<String> getActiveNodes(
      final String workflowId, final List<String> executionIds) {
    log.atDebug().addKeyValue("workflowId", workflowId).log("Fetching active nodes for workflow");
    final List<String> activeNodes = controlBusService.getActiveNodes(workflowId, executionIds);
    log.atDebug()
        .addKeyValue("workflowId", workflowId)
        .addKeyValue("activeNodeCount", activeNodes.size())
        .log("Active nodes retrieved");
    return activeNodes;
  }

  /* default */ List<String> getActiveNodes() {
    log.atDebug().log("Fetching all active nodes");
    final List<String> allActiveNodes = controlBusService.getActiveNodes();
    log.atDebug()
        .addKeyValue("totalActiveNodes", allActiveNodes.size())
        .log("All active nodes retrieved");
    return allActiveNodes;
  }

  /**
   * Ends a run the tracker still shows as running although nothing executes it any more (its status
   * event was lost), so it can be stopped instead of staying RUNNING for good.
   */
  /* default */ Mono<String> finishOrphan(final String executionId) {
    final WorkflowProgress progress = taskTracker.getProgressByExecutionId(executionId);
    final boolean orphan = progress != null && !TaskStatus.isTerminalName(progress.status());
    if (orphan) {
      log.atWarn().addKeyValue("executionId", executionId).log("Stopping orphaned execution");
    }
    return orphan
        ? taskTracker.finishWorkflow(executionId, TaskStatus.STOPPED).thenReturn(executionId)
        : Mono.error(new NotFoundException("Execution not found: " + executionId));
  }
}
