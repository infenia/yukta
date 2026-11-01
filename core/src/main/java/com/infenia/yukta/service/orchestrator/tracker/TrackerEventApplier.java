// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.event.TrackerEvent;
import com.infenia.yukta.event.WorkflowLogEvent;
import com.infenia.yukta.event.WorkflowStatusEvent;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** Applies tracker events to the state of the executions, in the order they were emitted. */
@Slf4j
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class TrackerEventApplier {

  /** Counts execution-log writes that failed, by what was being recorded. */
  private static final String LOG_APPEND_FAILED_METRIC = "yukta.tracker.log.append.failed";

  /** Tag value for a failed write of the end of a node. */
  private static final String LOG_APPEND_NODE = "node";

  /** Tag value for a failed write of the end of a run. */
  private static final String LOG_APPEND_RUN = "run";

  /** Finds the state of an execution. */
  private final Function<String, WorkflowState> states;

  /** Metrics and observations of runs and nodes. */
  private final RunTelemetry telemetry;

  /** Durable record of run starts and ends. */
  private final ExecutionLogStore executionLog;

  /** Receives what happens to every execution. */
  private final ExecutionEventFeed eventFeed;

  /** Live log and status streams. */
  private final ExecutionStreams streams;

  /** Log events accepted but not yet applied. */
  private final AtomicInteger pendingLogs;

  /** Schedules the cleanup of an ended execution. */
  private final Consumer<String> cleanup;

  /** Rates the log lines of each node, keyed by execution and node. */
  private final Map<String, LogLineClassifier> classifiers = new ConcurrentHashMap<>();

  /* default */ TrackerEventApplier(
      final Function<String, WorkflowState> states,
      final RunTelemetry telemetry,
      final ExecutionLogStore executionLog,
      final ExecutionEventFeed eventFeed,
      final ExecutionStreams streams,
      final AtomicInteger pendingLogs,
      final Consumer<String> cleanup) {
    this.states = states;
    this.telemetry = telemetry;
    this.executionLog = executionLog;
    this.eventFeed = eventFeed;
    this.streams = streams;
    this.pendingLogs = pendingLogs;
    this.cleanup = cleanup;
  }

  /** Applies a batch of events strictly in the order in which they were emitted. */
  /* default */ void apply(final List<TrackerEvent> events) {
    final Set<WorkflowState> touched = new LinkedHashSet<>();
    for (final TrackerEvent event : events) {
      applyIsolated(event, touched);
    }
    log.atDebug()
        .addKeyValue("eventsProcessed", events.size())
        .addKeyValue("executionsUpdated", touched.size())
        .log("Processed tracker event batch");
    touched.forEach(streams::notifyStatusChange);
  }

  /** One bad event must not cost the rest of its batch, such as a run's final status. */
  private void applyIsolated(final TrackerEvent event, final Set<WorkflowState> touched) {
    try {
      switch (event) {
        case TaskStatusEvent task -> applyTask(task, touched);
        case WorkflowStatusEvent workflow -> applyWorkflow(workflow, touched);
        case WorkflowLogEvent logEvent -> applyLog(logEvent);
      }
    } catch (
        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        final RuntimeException e) {
      log.atError().setCause(e).addKeyValue("event", event).log("Failed to apply tracker event");
    }
  }

  private void applyTask(final TaskStatusEvent event, final Set<WorkflowState> touched) {
    final WorkflowState state = states.apply(event.executionId());
    if (state == null) {
      log.atDebug()
          .addKeyValue("executionId", event.executionId())
          .addKeyValue("nodeId", event.nodeId())
          .log("Received task status event for unknown execution");
      return;
    }
    state.updateTask(event.nodeId(), event.module(), event.status(), event.metadata());
    // The state has changed, so subscribers hear about it however the rest of this event goes.
    touched.add(state);
    eventFeed.publish(
        new ExecutionEvent.NodeStatusChanged(
            event.executionId(),
            state.sessionId,
            state.workflowId,
            event.nodeId(),
            event.module(),
            event.status().name(),
            event.metadata(),
            Instant.now()));
    final TaskProgress task = state.taskMap.get(event.nodeId());
    // An event the state refused (a late RUNNING for an ended node) must not open a span that
    // nothing will ever close.
    if (task != null && (event.status().isTerminal() || task.status() == event.status())) {
      telemetry.traceNode(event, event.status().isTerminal());
    }
    if (event.status().isTerminal()) {
      telemetry.nodeEnded(task);
    }
    if (event.status() == TaskStatus.SUCCESS) {
      appendToLog(
          ExecutionLogEvent.nodeFinished(
              state.executionId, state.sessionId, state.workflowId, event.nodeId()),
          LOG_APPEND_NODE,
          "Could not record the end of a node in the execution log");
    }
  }

  private void applyWorkflow(final WorkflowStatusEvent event, final Set<WorkflowState> touched) {
    final WorkflowState state = states.apply(event.executionId());
    if (state == null) {
      log.atWarn()
          .addKeyValue("executionId", event.executionId())
          .log("Received workflow status event for unknown execution");
      return;
    }
    if (state.status.isTerminal()) {
      // The run has ended: a second end (a stop racing a cancel, say) must not finalize it again
      // or write a second end to the log.
      log.atDebug()
          .addKeyValue("executionId", event.executionId())
          .addKeyValue("status", event.status())
          .addKeyValue("finalStatus", state.status)
          .log("Ignored workflow status event for a run that has ended");
      return;
    }
    final boolean terminal = event.status().isTerminal();
    if (terminal) {
      // Every node event before this one is already applied, so what is still open is final.
      state.finalizeTasks(event.status());
      state.setEndTime(event.timestamp());
    }
    telemetry.runStatus(
        event.executionId(), event.status(), terminal, state.startTime, event.timestamp());
    if (terminal) {
      appendToLog(
          ExecutionLogEvent.finished(
              state.executionId,
              state.sessionId,
              state.workflowId,
              event.status().name(),
              nodeStatusNames(state)),
          LOG_APPEND_RUN,
          "Could not record the end of the run in the execution log");
    }
    state.setStatus(event.status());
    eventFeed.publish(
        new ExecutionEvent.WorkflowStatusChanged(
            event.executionId(),
            state.sessionId,
            state.workflowId,
            event.status().name(),
            Instant.now()));
    touched.add(state);
    if (event.status().isTerminal()) {
      cleanup.accept(event.executionId());
      log.atDebug()
          .addKeyValue("executionId", event.executionId())
          .addKeyValue("finalStatus", event.status())
          .log("Workflow reached terminal status, scheduled cleanup");
    }
  }

  /**
   * A log that cannot be written must not keep the run from ending or its status from being
   * delivered. The failure is logged and counted, apart from dropped tracker events, so an operator
   * can alert on runs that lack a durable record.
   */
  private void appendToLog(
      final ExecutionLogEvent logEvent, final String type, final String message) {
    try {
      executionLog.append(logEvent);
    } catch (
        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        final RuntimeException e) {
      telemetry.logAppendFailed(LOG_APPEND_FAILED_METRIC, type);
      log.atError().setCause(e).addKeyValue("executionId", logEvent.executionId()).log(message);
    }
  }

  /** Drops what the applier keeps for an execution that was cleaned up. */
  /* default */ void forget(final String executionId) {
    classifiers.keySet().removeIf(key -> key.startsWith(executionId + "\0"));
  }

  private void applyLog(final WorkflowLogEvent unrated) {
    pendingLogs.decrementAndGet();
    // The level a plugin reported wins; otherwise the line is rated here, once, in order.
    final WorkflowLogEvent event =
        unrated.level() != null
            ? unrated
            : unrated.withLevel(
                classifiers
                    .computeIfAbsent(
                        unrated.executionId() + "\0" + unrated.nodeId(),
                        _ -> new LogLineClassifier())
                    .rate(unrated.line()));
    streams.feed(event);
    final WorkflowState owner = states.apply(event.executionId());
    eventFeed.publish(
        new ExecutionEvent.LogLine(
            event.executionId(),
            owner == null ? null : owner.sessionId,
            owner == null ? null : owner.workflowId,
            event.nodeId(),
            event.line(),
            event.level(),
            Instant.now()));
    streams.emitLine(event);
  }

  private static Map<String, String> nodeStatusNames(final WorkflowState state) {
    return state.nodeStatuses().entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().name(), (a, b) -> b, LinkedHashMap::new));
  }
}
