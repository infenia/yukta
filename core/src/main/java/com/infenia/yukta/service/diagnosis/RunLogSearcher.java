// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import com.infenia.yukta.logging.api.GrepPattern;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Searches the logs of a run, and optionally of the runs it started, the way {@code grep -c},
 * {@code grep -l} and {@code grep -C} do: counts per node first, then the lines that matter, always
 * within a budget. Lines are grouped per node, so context comes from the node that wrote the match.
 *
 * <p>It does not check who may read the run: the caller (REST, MCP) does that first.
 */
@Service
@RequiredArgsConstructor
public class RunLogSearcher {

  private final PluginLogStore store;
  private final TaskTrackerService tracker;

  /**
   * Searches a run.
   *
   * @param query the search
   * @param clean applied to every line before it is matched or returned (secret redaction)
   * @return the answer; an {@link IllegalArgumentException} for a bad pattern or cursor
   */
  public Mono<RunLogSearch> search(final RunLogQuery query, final UnaryOperator<String> clean) {
    return Mono.defer(() -> {
      final String grep = query.grep();
      final GrepPattern pattern = grep == null ? null : GrepPattern.compile(grep);
      final LogSearchPass.Cursor cursor = LogSearchPass.Cursor.parse(query.cursor());
      return Flux.fromIterable(executions(query))
          .concatMap(id ->
              store.readIndexed(id).collectList().map(logs -> new LogSearchPass.Run(id, logs)))
          .collectList()
          .map(runs -> new LogSearchPass(query, pattern, cursor, clean).answer(runs));
    });
  }

  /**
   * The run first, then (with children) the runs it started, oldest first: only those of the run's
   * own session, so a search never reaches into a session the caller was not checked for.
   */
  private List<String> executions(final RunLogQuery query) {
    final List<String> ids = new ArrayList<>();
    ids.add(query.executionId());
    final String session = query.includeChildren() ? sessionOf(query.executionId()) : null;
    if (session != null) {
      tracker.getTree(query.executionId()).stream()
          .map(WorkflowExecutionSummary::executionId)
          .filter(id -> !query.executionId().equals(id))
          .filter(id -> session.equals(sessionOf(id)))
          .forEach(ids::add);
    }
    return ids;
  }

  private @Nullable String sessionOf(final String executionId) {
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    return progress == null ? null : progress.sessionId();
  }
}
