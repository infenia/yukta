// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.diagnosis.FailureDigest;
import com.infenia.yukta.service.diagnosis.FailureDigestService;
import com.infenia.yukta.service.diagnosis.LogSearchMode;
import com.infenia.yukta.service.diagnosis.RunLogQuery;
import com.infenia.yukta.service.diagnosis.RunLogSearch;
import com.infenia.yukta.service.diagnosis.RunLogSearcher;
import com.infenia.yukta.service.diagnosis.SearchArguments;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The cheap reads of a run: why it failed (the digest) and a grep over its logs, the same answers
 * MCP {@code get_failure_digest} and {@code search_logs} give.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Run Diagnosis API", description = "Why a run failed, and a search over its logs")
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public class RunDiagnosisController {

  /** Session ownership check. */
  private final ExecutionAccess access;

  /** Builds digests. */
  private final FailureDigestService digests;

  /** Searches logs. */
  private final RunLogSearcher searcher;

  /**
   * Why a run failed.
   *
   * @param executionId the execution
   * @return the digest
   */
  @GetMapping("/executions/{executionId}/digest")
  @Operation(
      summary = "Why a run failed, in a few kilobytes",
      description =
          "The run's status, its failed or timed-out nodes (also of failed sub-runs) with their"
              + " error, the failures recognised in their output (tests, compile errors, lint"
              + " violations, tasks, with file:line) and the reads that show more. 404 for an"
              + " unknown execution.")
  public Mono<ApiResponse<FailureDigest>> getDigest(
      @Parameter(description = "Execution ID") @PathVariable final String executionId) {
    return access
        .require(null, executionId)
        .flatMap(progress -> digests.digest(progress, UnaryOperator.identity()))
        .map(digest -> ApiResponse.success(200, "Digest built", digest));
  }

  /**
   * Search the logs of a run.
   *
   * @param executionId the execution
   * @param includeChildren whether its sub-runs are searched too
   * @param nodes comma-separated node ids
   * @param stream stdout, stderr or all
   * @param level the lowest level a line may have
   * @param grep a regular expression the lines must contain
   * @param context context lines around a match
   * @param mode COUNT, NODES or LINES
   * @param maxMatches the most matching lines returned
   * @param cursor the previous answer's nextCursor
   * @return counts per node and, in LINES mode, the lines
   */
  @GetMapping("/executions/{executionId}/logs/search")
  @Operation(
      summary = "Search the logs of a run like grep",
      description =
          "COUNT (default) returns lines, errors, warnings and matches per node; NODES only the"
              + " nodes that matched; LINES the matching lines with context from the same node,"
              + " within maxMatches (default 50) and 16 KB, with nextCursor for the next page and"
              + " a hint for the next search. 400 for a bad argument, 404 for an unknown"
              + " execution.")
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public Mono<ApiResponse<RunLogSearch>> search(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Also search the runs it started")
          @RequestParam(defaultValue = "false")
          final boolean includeChildren,
      @Parameter(description = "Comma-separated node ids; all if absent")
          @RequestParam(required = false)
          final String nodes,
      @Parameter(description = "stdout, stderr or all") @RequestParam(defaultValue = "all")
          final String stream,
      @Parameter(description = "Lowest level: ERROR, WARN, INFO or DEBUG")
          @RequestParam(required = false)
          final String level,
      @Parameter(description = "Regular expression a line must contain, at most 200 characters")
          @RequestParam(required = false)
          final String grep,
      @Parameter(description = "Context lines around a match (LINES), at most 20")
          @RequestParam(defaultValue = "0")
          final int context,
      @Parameter(description = "COUNT, NODES or LINES") @RequestParam(defaultValue = "COUNT")
          final LogSearchMode mode,
      @Parameter(description = "Most matching lines (LINES), default 50, at most 500")
          @RequestParam(defaultValue = "0")
          final int maxMatches,
      @Parameter(description = "nextCursor of the previous answer") @RequestParam(required = false)
          final String cursor) {
    return Mono.defer(() -> {
      final RunLogQuery query =
          RunLogQuery.builder()
              .executionId(executionId)
              .includeChildren(includeChildren)
              .nodes(SearchArguments.nodes(nodes))
              .stream(SearchArguments.stream(stream))
              .level(SearchArguments.level(level))
              .grep(grep)
              .context(context)
              .mode(mode)
              .maxMatches(maxMatches)
              .cursor(cursor)
              .build();
      return access
          .require(null, executionId)
          .then(Mono.defer(() -> searcher.search(query, UnaryOperator.identity())))
          .map(search -> ApiResponse.success(200, "Search done", search));
    });
  }
}
