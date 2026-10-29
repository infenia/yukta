// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.event.WorkflowLogEvent;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import com.infenia.yukta.util.SinkEmit;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** The live log and status streams of the executions a tracker follows. */
@Slf4j
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class ExecutionStreams {

  /** Completes a sink, retrying only while another thread is emitting at the same moment. */
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

  /** Execution ID to its log lines. */
  private final Map<String, Sinks.Many<String>> logSinks = new ConcurrentHashMap<>();

  /** Execution ID to its status updates. */
  private final Map<String, Sinks.Many<WorkflowProgress>> statusSinks;

  /** Best-effort feed of log events; slow readers never block events. */
  private final Sinks.Many<WorkflowLogEvent> logFeed = Sinks.many().multicast().directBestEffort();

  /** Recent status updates per execution, replayed to late subscribers. */
  private final StatusHistoryCache statusHistoryCache;

  /** Finds the state of an execution. */
  private final Function<String, WorkflowState> states;

  /** Builds the progress of a state. */
  private final Function<WorkflowState, WorkflowProgress> progressOf;

  /* default */ ExecutionStreams(
      final Map<String, Sinks.Many<WorkflowProgress>> statusSinks,
      final StatusHistoryCache statusHistoryCache,
      final Function<String, WorkflowState> states,
      final Function<WorkflowState, WorkflowProgress> progressOf) {
    this.statusSinks = statusSinks;
    this.statusHistoryCache = statusHistoryCache;
    this.states = states;
    this.progressOf = progressOf;
  }

  /** Opens the streams of a new execution. */
  /* default */ void open(final String executionId) {
    logSinks.put(executionId, Sinks.many().multicast().directBestEffort());
    statusSinks.put(executionId, Sinks.many().multicast().directBestEffort());
  }

  /** Forgets the streams of an execution without completing them. */
  /* default */ void drop(final String executionId) {
    logSinks.remove(executionId);
    statusSinks.remove(executionId);
  }

  /** Completes and forgets the streams of an execution. */
  /* default */ void close(final String executionId) {
    final Sinks.Many<String> logSink = logSinks.remove(executionId);
    if (logSink != null) {
      logSink.emitComplete(RETRY_HANDLER);
    }
    final Sinks.Many<WorkflowProgress> statusSink = statusSinks.remove(executionId);
    if (statusSink != null) {
      statusSink.emitComplete(RETRY_HANDLER);
    }
  }

  /** Hands a log event to the all-executions feed. */
  /* default */ void feed(final WorkflowLogEvent event) {
    logFeed.tryEmitNext(event);
  }

  /** Hands a log line to the subscribers of its execution. */
  /* default */ void emitLine(final WorkflowLogEvent event) {
    final Sinks.Many<String> sink = logSinks.get(event.executionId());
    if (sink != null) {
      sink.tryEmitNext(event.line());
    }
  }

  /** Caches the state's progress and tells its status subscribers. */
  /* default */ void notifyStatusChange(final WorkflowState state) {
    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(state.executionId);
    if (sink != null) {
      final WorkflowProgress progress = progressOf.apply(state);
      statusHistoryCache.put(state.executionId, progress);
      SinkEmit.offer(sink, progress);
    }
  }

  /**
   * The log lines of an execution from now on; completes when the execution has ended.
   *
   * @param executionId the execution
   * @return the lines, or empty if the execution is unknown
   */
  /* default */ Flux<String> logStream(final String executionId) {
    log.atDebug().addKeyValue("executionId", executionId).log("Getting log stream");

    final Sinks.Many<String> sink = logSinks.get(executionId);
    if (sink == null) {
      log.atWarn().addKeyValue("executionId", executionId).log("No log sink found for execution");
      return Flux.empty();
    }

    return sink.asFlux()
        .takeUntil(
            _ -> {
              final WorkflowState state = states.apply(executionId);
              if (state != null
                  && TaskStatus.isTerminalName(state.status)
                  && state.endTime != null) {
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("status", state.status)
                    .log("Workflow terminal status detected, terminating log stream");
                return true;
              }
              return false;
            })
        .doOnComplete(
            () -> log.atInfo().addKeyValue("executionId", executionId).log("Log stream completed"));
  }

  /**
   * Every log entry of every execution, with the context that is known.
   *
   * @return the entries
   */
  /* default */ Flux<PluginLogEntry> logFlux() {
    return logFeed
        .asFlux()
        .map(
            event -> {
              final WorkflowState state = states.apply(event.executionId());
              final String sessionId = state != null ? state.sessionId : "unknown";
              final String module =
                  state != null && state.taskMap.get(event.nodeId()) != null
                      ? state.taskMap.get(event.nodeId()).module()
                      : "unknown";

              return new PluginLogEntry(
                  event.executionId(),
                  sessionId,
                  event.nodeId(), // Use nodeId as pluginId
                  module, // Use module as pluginName
                  event.stderr() ? LogStream.STDERR : LogStream.STDOUT,
                  event.line(),
                  Objects.requireNonNull(
                      event.level(), "the tracker rates a line before it is fed"),
                  event.timestamp().atZone(ZoneId.systemDefault()).toInstant(),
                  null,
                  null);
            });
  }

  /**
   * Status updates of an execution, optionally preceded by the cached ones.
   *
   * @param executionId the execution
   * @param includeHistory if true, emit cached history before live updates
   * @return the updates, completing on a terminal state, or empty if the execution is unknown
   */
  /* default */ Flux<WorkflowProgress> statusStream(
      final String executionId, final boolean includeHistory) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("includeHistory", includeHistory)
        .log("Getting status stream");

    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(executionId);
    if (sink == null) {
      log.atWarn()
          .addKeyValue("executionId", executionId)
          .log("No status sink found for execution");
      return Flux.empty();
    }

    Flux<WorkflowProgress> statusFlux = sink.asFlux();

    if (includeHistory) {
      final List<WorkflowProgress> history = statusHistoryCache.get(executionId);
      log.atDebug()
          .addKeyValue("executionId", executionId)
          .addKeyValue("historySize", history.size())
          .log("Including history in stream");
      statusFlux = Flux.fromIterable(history).concatWith(statusFlux);
    }

    return statusFlux
        .takeUntil(
            progress -> {
              final boolean isTerminal = isWorkflowTerminal(progress);
              if (isTerminal) {
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("status", progress.status())
                    .log("Workflow terminal status detected, terminating stream");
              }
              return isTerminal;
            })
        .doOnComplete(
            () ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .log("Status stream completed"));
  }

  /** A workflow is over when its status is terminal and its end time is set. */
  private static boolean isWorkflowTerminal(final WorkflowProgress progress) {
    final String status = progress.status();
    final LocalDateTime endTime = progress.endTime();
    if (TaskStatus.isTerminalName(status) && endTime != null) {
      log.atDebug()
          .addKeyValue("status", status)
          .addKeyValue("endTime", endTime)
          .log("Workflow reached terminal state");
      return true;
    }
    return false;
  }
}
