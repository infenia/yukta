// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.LogEntryResponse;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Execution log streams: stored history first, then live lines until the execution ends. The live
 * feed is subscribed before the history is read and buffered, so lines written while the history
 * loads are neither lost nor sent twice. Events carry sequential IDs ({@code Last-Event-ID} resumes
 * a dropped connection) and a keep-alive comment every 15 seconds.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/executions/{executionId}/logs")
@Tag(name = "Execution Logs API", description = "Stream execution logs")
@RequiredArgsConstructor
public class LogManagementController {

  /** Live lines kept while the history is being read. */
  private static final int LIVE_BUFFER = 1024;

  /** The control bus. */
  private final ControlBusGateway controlBus;

  /** The log store. */
  private final PluginLogStore logStore;

  /** The task tracker. */
  private final DefaultTaskTrackerService taskTracker;

  /** The workflow mapper. */
  private final WorkflowMapper workflowMapper;

  /** The access. */
  private final ExecutionAccess access;

  /**
   * Stream formatted log lines.
   *
   * @param sessionId the owning session
   * @param executionId the execution
   * @param lastEventId the {@code Last-Event-ID} of a reconnecting client
   * @return one event per log line
   */
  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream execution logs with history",
      description =
          "Streams stored log lines first, then live ones. Stops when the execution reaches a"
              + " terminal state. Answers 404 for an unknown execution or one of another session.")
  public Flux<ServerSentEvent<String>> streamExecutionLogs(
      @Parameter(description = "Session identifier") @PathVariable final String sessionId,
      @Parameter(description = "Execution identifier") @PathVariable final String executionId,
      @RequestHeader(value = "Last-Event-ID", required = false) final String lastEventId) {
    return access
        .require(sessionId, executionId)
        .flatMapMany(
            progress ->
                SseStreams.events(
                    entries(executionId, progress.status()).map(PluginLogEntry::format),
                    lastEventId,
                    SseStreams.HEARTBEAT));
  }

  /**
   * Stream structured log entries.
   *
   * @param sessionId the owning session
   * @param executionId the execution
   * @param lastEventId the {@code Last-Event-ID} of a reconnecting client
   * @return one event per log entry
   */
  @GetMapping(value = "/entries", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream structured execution log entries with history",
      description =
          "Streams stored structured entries first, then live ones. Stops when the execution"
              + " reaches a terminal state. Answers 404 for an unknown execution or one of"
              + " another session.")
  public Flux<ServerSentEvent<LogEntryResponse>> streamExecutionLogEntries(
      @Parameter(description = "Session identifier") @PathVariable final String sessionId,
      @Parameter(description = "Execution identifier") @PathVariable final String executionId,
      @RequestHeader(value = "Last-Event-ID", required = false) final String lastEventId) {
    return access
        .require(sessionId, executionId)
        .flatMapMany(
            progress ->
                SseStreams.events(
                    entries(executionId, progress.status()).map(workflowMapper::toLogEntry),
                    lastEventId,
                    SseStreams.HEARTBEAT));
  }

  private Flux<PluginLogEntry> entries(final String executionId, final TaskStatus status) {
    return status.isTerminal() ? logStore.readExecution(executionId) : historyThenLive(executionId);
  }

  private Flux<PluginLogEntry> historyThenLive(final String executionId) {
    return Flux.defer(
        () -> {
          final Instant seamStart = Instant.now();
          final AtomicReference<Disposable> upstream = new AtomicReference<>();
          // autoConnect(0) subscribes right away, so nothing written from here on is missed.
          final Flux<PluginLogEntry> live =
              taskTracker
                  .getLogFlux()
                  .filter(entry -> executionId.equals(entry.executionId()))
                  .takeUntilOther(terminated(executionId))
                  .replay(LIVE_BUFFER)
                  .autoConnect(0, upstream::set);
          return logStore
              .readExecution(executionId)
              .collectList()
              .flatMapMany(
                  history -> {
                    final Instant snapshot = Instant.now();
                    final Map<String, Integer> seam = new ConcurrentHashMap<>();
                    history.stream()
                        .filter(entry -> !entry.timestamp().isBefore(seamStart))
                        .forEach(entry -> seam.merge(entry.message(), 1, Integer::sum));
                    return Flux.fromIterable(history)
                        .concatWith(live.filter(entry -> !alreadySent(entry, snapshot, seam)));
                  })
              .doFinally(_ -> upstream.get().dispose());
        });
  }

  /**
   * A live entry that was written before the history snapshot and also shows up in it. Matching is
   * by message text because live and stored entries share no sequence number.
   */
  private static boolean alreadySent(
      final PluginLogEntry entry, final Instant snapshot, final Map<String, Integer> seam) {
    final int remaining = seam.getOrDefault(entry.message(), 0);
    final boolean duplicate = remaining > 0 && !entry.timestamp().isAfter(snapshot);
    if (duplicate) {
      seam.put(entry.message(), remaining - 1);
    }
    return duplicate;
  }

  private Mono<Void> terminated(final String executionId) {
    return controlBus
        .watchExecution(executionId, true)
        .filter(p -> p.status().isTerminal() && p.endTime() != null)
        .next()
        .then();
  }
}
