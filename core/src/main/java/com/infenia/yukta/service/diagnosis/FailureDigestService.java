// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Builds the {@link FailureDigest} of a run: its failed nodes and those of its failed sub-runs, the
 * failures the {@link FailureExtractor}s recognise in their output, and where to read next, within
 * a budget of about 4 KB.
 *
 * <p>It does not check who may read the run: the caller (REST, MCP) passes a progress it obtained
 * for the caller's session.
 */
@Service
@RequiredArgsConstructor
public class FailureDigestService {

  private final PluginLogStore store;
  private final TaskTrackerService tracker;

  /** Every extractor bean, in {@code @Order}. */
  private final List<FailureExtractor> extractors;

  /**
   * The digest of a run.
   *
   * @param run the run's progress, read for the caller's session
   * @param clean applied to every line before it is read (secret redaction)
   * @return the digest
   */
  public Mono<FailureDigest> digest(final WorkflowProgress run, final UnaryOperator<String> clean) {
    return Flux.fromIterable(runs(run))
        .concatMap(progress ->
            store
                .readExecution(progress.executionId())
                .collectList()
                .map(entries -> new FailureDigestAssembler.RunLog(progress, entries)))
        .collectList()
        .map(logs -> new FailureDigestAssembler(extractors, clean).assemble(run, logs));
  }

  /** The run, then its sub-runs of the same session that failed, oldest first. */
  private List<WorkflowProgress> runs(final WorkflowProgress run) {
    final List<WorkflowProgress> runs = new ArrayList<>();
    runs.add(run);
    tracker.getTree(run.executionId()).stream()
        .filter(child -> !run.executionId().equals(child.executionId()))
        .filter(child -> failed(child.status()))
        .map(WorkflowExecutionSummary::executionId)
        .map(tracker::getProgressByExecutionId)
        .filter(Objects::nonNull)
        .filter(child -> run.sessionId().equals(child.sessionId()))
        .forEach(runs::add);
    return runs;
  }

  /* default */ static boolean failed(final TaskStatus status) {
    return status == TaskStatus.FAILURE || status == TaskStatus.TIMED_OUT;
  }

  /* default */ static boolean failed(final TaskProgress task) {
    return failed(task.status());
  }

  /* default */ static String text(final PluginLogEntry entry) {
    return entry.message() == null ? "" : entry.message();
  }
}
