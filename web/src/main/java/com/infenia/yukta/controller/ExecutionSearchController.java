// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Finds runs across all sessions. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Execution Search API", description = "Find runs across sessions")
// Query parameters bind to this record, which AOT cannot see from the method signature.
@RegisterReflectionForBinding(HistoryFilter.class)
public class ExecutionSearchController {

  /** Most runs returned. */
  private static final int MAX_LIMIT = 1000;

  /** Runs returned when no limit is given. */
  private static final int DEFAULT_LIMIT = 100;

  /** The sessions. */
  private final SessionService sessions;

  /** The control bus, which knows the history of each session. */
  private final ControlBusGateway controlBus;

  /**
   * A run found by the search.
   *
   * @param sessionId the session it belongs to
   * @param executionId the run
   * @param workflowId the workflow
   * @param status the status
   * @param startTime when it started, if it did
   * @param endTime when it ended, if it did
   */
  public record Hit(
      String sessionId,
      String executionId,
      String workflowId,
      String status,
      LocalDateTime startTime,
      LocalDateTime endTime) {}

  /**
   * Find runs across all sessions, newest first.
   *
   * @param sessionId only this session
   * @param limit most runs returned (default 100, at most 1000)
   * @param filter narrows the runs by workflow, status, text in the execution id, and start range
   * @return the matching runs
   */
  @GetMapping("/executions")
  @Operation(
      summary = "Search runs across sessions",
      description =
          "Lists the runs of all sessions (or of sessionId), newest first, narrowed by workflowId,"
              + " status, text (in the execution id) and since/until (start time, ISO date-time)."
              + " The history of each session is bounded by yukta.execution-log.retained-runs.")
  public Mono<ApiResponse<List<Hit>>> search(
      @RequestParam(required = false) final String sessionId,
      @RequestParam(required = false) final Integer limit,
      final HistoryFilter filter) {
    final int max = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
    return sessions
        .getSessionIds()
        .filter(id -> sessionId == null || sessionId.equals(id))
        .concatMap(
            id ->
                controlBus
                    .getHistory(id)
                    .flatMapIterable(history -> history)
                    .filter(filter::matches)
                    .map(run -> hit(id, run)))
        .sort(Comparator.comparing(Hit::startTime, Comparator.nullsLast(Comparator.reverseOrder())))
        .take(max)
        .collectList()
        .map(hits -> ApiResponse.success(200, "Executions found", hits));
  }

  private static Hit hit(final String sessionId, final WorkflowExecutionSummary run) {
    return new Hit(
        sessionId,
        run.executionId(),
        run.workflowId(),
        run.status(),
        run.startTime(),
        run.endTime());
  }
}
