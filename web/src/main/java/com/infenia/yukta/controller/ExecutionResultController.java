// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.service.result.ExecutionResults;
import com.infenia.yukta.service.result.ResultEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What reached the terminal nodes of an execution: a live event stream (history first, then new
 * events until the execution ends) and a plain read of what was recorded so far. Events carry
 * sequential IDs, so {@code Last-Event-ID} resumes a dropped connection.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/executions/{executionId}/results")
@Tag(name = "Execution Results API", description = "Follow the result of an execution")
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public class ExecutionResultController {

  /** Records the results. */
  private final ExecutionResultService results;

  /** Session ownership check. */
  private final ExecutionAccess access;

  /**
   * Stream the results of an execution.
   *
   * @param sessionId the owning session
   * @param executionId the execution
   * @param nodeId optional terminal node to follow
   * @param lastEventId the {@code Last-Event-ID} of a reconnecting client
   * @return one event per result, per finished terminal, and a last one for the execution
   */
  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream the result of an execution",
      description =
          "Each event's kind is RESULT (a message reached a terminal node), NODE_END or"
              + " EXECUTION_END. Kept events are replayed first, then new ones follow until the"
              + " execution ends. Answers 404 for an unknown execution or one of another session.")
  public Flux<ServerSentEvent<ResultEvent>> streamResults(
      @Parameter(description = "Session identifier") @PathVariable final String sessionId,
      @Parameter(description = "Execution identifier") @PathVariable final String executionId,
      @Parameter(description = "Only this terminal node (the execution-end event always comes)")
          @RequestParam(required = false)
          final String nodeId,
      @RequestHeader(value = "Last-Event-ID", required = false) final String lastEventId) {
    return access
        .require(sessionId, executionId)
        .flatMapMany(
            progress -> {
              // A finished run that was evicted (or never had a terminal) has nothing to replay.
              final boolean nothingToReplay =
                  TaskStatus.isTerminalName(progress.status()) && !results.has(executionId);
              final Flux<ResultEvent> events =
                  nothingToReplay
                      ? Flux.empty()
                      : results.stream(executionId)
                          .filter(
                              event ->
                                  nodeId == null
                                      || event.nodeId() == null
                                      || nodeId.equals(event.nodeId()));
              return SseStreams.events(events, lastEventId, SseStreams.HEARTBEAT);
            });
  }

  /**
   * Read the results recorded so far.
   *
   * @param sessionId the owning session
   * @param executionId the execution
   * @param afterCursor the {@code nextCursor} of an earlier read, to get only newer events
   * @return the results and the cursor to continue from
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Read the result of an execution",
      description =
          "Returns what the terminal nodes received so far; complete is true once the execution"
              + " ended. The stdout and stderr fields of the payloads are left out unless named in"
              + " include (stdout, stderr or stdout,stderr); fetch again to drill down."
              + " Answers 404 for an unknown execution or one of another session.")
  public Mono<ExecutionResults> getResults(
      @Parameter(description = "Session identifier") @PathVariable final String sessionId,
      @Parameter(description = "Execution identifier") @PathVariable final String executionId,
      @Parameter(description = "nextCursor of an earlier read") @RequestParam(defaultValue = "0")
          final long afterCursor,
      @Parameter(description = "Output fields to keep in the payloads: stdout, stderr or both")
          @RequestParam(required = false)
          final String include) {
    return access
        .require(sessionId, executionId)
        .map(_ -> results.read(executionId, afterCursor).withOutput(include));
  }
}
