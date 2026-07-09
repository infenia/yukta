// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for execution log streaming.
 *
 * <p>Provides real-time streaming access to execution logs via the ControlBusGateway. Logs are
 * captured during workflow execution and streamed as they are generated.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/executions/{executionId}/logs")
@Slf4j
@Tag(name = "Execution Logs API", description = "Stream execution logs")
@RequiredArgsConstructor
public class LogManagementController {

  /** Log key for the execution identifier. */
  private static final String EXECUTION_ID = "executionId";

  /** The control bus gateway for log streaming. */
  private final ControlBusGateway controlBus;

  /** The plugin log store for historical log retrieval. */
  private final PluginLogStore logStore;

  /**
   * Stream execution logs with history.
   *
   * <p>Streams historical log entries first (if available), then continues with live updates.
   * Automatically stops streaming when execution reaches a terminal state
   * (completed/failed/cancelled/stopped).
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return flux of log lines with history-then-live ordering that completes on execution end
   */
  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream execution logs with history",
      description =
          "Streams historical log entries first (if available), then continues with live updates."
              + " Stops when execution reaches a terminal state.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "Stream of log lines with history-then-live ordering")
  public Flux<String> streamExecutionLogs(
      @Parameter(description = "Session identifier") @PathVariable final String sessionId,
      @Parameter(description = "Execution identifier") @PathVariable final String executionId) {

    log.atInfo()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue(EXECUTION_ID, executionId)
        .log("Streaming execution logs");

    return Mono.fromCallable(() -> controlBus.getCurrentProgress(executionId))
        .flatMapMany(
            progress -> {
              final boolean isTerminal = isExecutionTerminal(progress.status());

              if (isTerminal) {
                log.atDebug()
                    .addKeyValue(EXECUTION_ID, executionId)
                    .addKeyValue("status", progress.status())
                    .log("Execution already terminated, streaming historical logs only");
                return logStore
                    .readExecution(executionId)
                    .map(PluginLogEntry::format)
                    .doOnNext(
                        _ ->
                            log.atDebug()
                                .addKeyValue(EXECUTION_ID, executionId)
                                .log("Emitting historical log entry"))
                    .doOnComplete(
                        () ->
                            log.atDebug()
                                .addKeyValue(EXECUTION_ID, executionId)
                                .log("Historical logs complete"));
              }

              log.atDebug()
                  .addKeyValue(EXECUTION_ID, executionId)
                  .addKeyValue("status", progress.status())
                  .log("Execution in progress, streaming history then live logs");

              final Flux<String> historicalLogs =
                  logStore
                      .readExecution(executionId)
                      .map(PluginLogEntry::format)
                      .doOnNext(
                          _ ->
                              log.atDebug()
                                  .addKeyValue(EXECUTION_ID, executionId)
                                  .log("Emitting historical log entry"))
                      .doOnComplete(
                          () ->
                              log.atDebug()
                                  .addKeyValue(EXECUTION_ID, executionId)
                                  .log("Historical logs complete"));

              final var executionTermination =
                  controlBus
                      .watchExecution(executionId, true)
                      .filter(
                          p -> {
                            final boolean isTerminalState =
                                isExecutionTerminal(p.status()) && p.endTime() != null;
                            if (isTerminalState) {
                              log.atDebug()
                                  .addKeyValue(EXECUTION_ID, executionId)
                                  .addKeyValue("status", p.status())
                                  .log("Execution reached terminal state");
                            }
                            return isTerminalState;
                          })
                      .ignoreElements();

              final Flux<String> liveLogs =
                  controlBus
                      .watchLogs(sessionId, executionId)
                      .doOnNext(
                          _ ->
                              log.atTrace()
                                  .addKeyValue(EXECUTION_ID, executionId)
                                  .log("Emitting live log entry"))
                      .takeUntilOther(executionTermination);

              return historicalLogs.concatWith(liveLogs);
            })
        .doOnComplete(
            () -> log.atInfo().addKeyValue(EXECUTION_ID, executionId).log("Log stream completed"));
  }

  private boolean isExecutionTerminal(final String status) {
    return "COMPLETED".equals(status)
        || "FAILED".equals(status)
        || "CANCELLED".equals(status)
        || "WORKFLOW_STOPPED".equals(status);
  }
}
