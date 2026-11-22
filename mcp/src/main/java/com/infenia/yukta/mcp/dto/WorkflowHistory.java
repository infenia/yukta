// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Execution history of a session returned by the {@code get_workflow_history} MCP tool. Wrapping
 * the list in a record keeps the tool's output schema generatable (bare {@code Mono<List<T>>}
 * return types break schema generation on the async stateless MCP stack).
 *
 * @param executions the execution summaries, in the order the source gave them
 * @param omitted how many runs were left out by the status filter or the limit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowHistory(List<WorkflowExecutionSummary> executions, int omitted) {

  /** Runs listed when no limit is given. */
  public static final int DEFAULT_LIMIT = 50;

  /** Most runs listed by one call. */
  public static final int MAX_LIMIT = 500;

  /**
   * History with nothing left out.
   *
   * @param executions the execution summaries
   */
  public WorkflowHistory(final List<WorkflowExecutionSummary> executions) {
    this(executions, 0);
  }

  /** Compact constructor to ensure immutability. */
  public WorkflowHistory {
    executions = executions == null ? List.of() : List.copyOf(executions);
  }

  /**
   * The runs with a status, at most {@code limit} of them: the newest ones, kept in their order.
   *
   * @param executions every run
   * @param status a status name (case-insensitive), or {@code null} for every status
   * @param limit the most runs, {@code null} for {@value #DEFAULT_LIMIT}; at most {@value
   *     #MAX_LIMIT}
   * @return the runs kept and how many were left out
   */
  public static WorkflowHistory select(
      final List<WorkflowExecutionSummary> executions,
      @Nullable final String status,
      @Nullable final Integer limit) {
    final int max = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    final List<WorkflowExecutionSummary> matching =
        executions.stream()
            .filter(run ->
                status == null
                    || status.isBlank()
                    || run.status()
                        .name()
                        .equalsIgnoreCase(status.strip().toUpperCase(Locale.ROOT)))
            .toList();
    final Set<WorkflowExecutionSummary> newest =
        matching.stream()
            .sorted(
                Comparator.comparing((WorkflowExecutionSummary run) ->
                        Objects.requireNonNullElse(run.startTime(), Instant.MIN))
                    .reversed())
            .limit(max)
            .collect(Collectors.toSet());
    final List<WorkflowExecutionSummary> kept = matching.stream().filter(newest::contains).toList();
    return new WorkflowHistory(kept, executions.size() - kept.size());
  }
}
