// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rebuilds run history from the execution log when the server starts.
 *
 * <p>A run that was started but never finished was cut short by a crash or restart: it is recorded
 * as {@code ABORTED}. Recent runs are restored into the tracker so they appear in history, and the
 * log is rewritten without older ones so it does not grow without bound.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.AvoidInstantiatingObjectsInLoops"})
public class ExecutionRecovery implements SmartInitializingSingleton {

  /** The execution log. */
  private final ExecutionLogStore store;

  /** Receives the restored runs. */
  private final DefaultTaskTrackerService tracker;

  /** Number of most recent runs kept in the log and restored. */
  private final int retainedRuns;

  /**
   * Creates the recovery step.
   *
   * @param store the execution log
   * @param tracker receives the restored runs
   * @param retainedRuns number of most recent runs kept
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public ExecutionRecovery(
      final ExecutionLogStore store,
      final DefaultTaskTrackerService tracker,
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns) {
    this.store = store;
    this.tracker = tracker;
    this.retainedRuns = Math.max(1, retainedRuns);
  }

  @Override
  public void afterSingletonsInstantiated() {
    recover();
  }

  /** Marks interrupted runs aborted, trims the log and restores recent runs into the tracker. */
  public void recover() {
    final Map<String, List<ExecutionLogEvent>> byRun = new LinkedHashMap<>();
    for (final ExecutionLogEvent event : store.readAll()) {
      byRun.computeIfAbsent(event.executionId(), _ -> new ArrayList<>()).add(event);
    }

    final List<ExecutionLogEvent> kept = new ArrayList<>();
    final List<List<ExecutionLogEvent>> runs = new ArrayList<>(byRun.values());
    final int skip = Math.max(0, runs.size() - retainedRuns);
    int aborted = 0;
    for (final List<ExecutionLogEvent> run : runs.subList(skip, runs.size())) {
      final ExecutionLogEvent started = find(run, ExecutionLogEvent.Type.RUN_STARTED);
      final ExecutionLogEvent ended = end(run);
      if (started != null && ended == null) {
        run.add(ExecutionLogEvent.aborted(started));
        aborted++;
      }
      kept.addAll(run);
      restore(started, end(run));
    }
    store.replaceAll(kept);
    log.atInfo()
        .addKeyValue("restored", runs.size() - skip)
        .addKeyValue("aborted", aborted)
        .addKeyValue("dropped", skip)
        .log("Recovered execution history");
  }

  private void restore(final ExecutionLogEvent started, final ExecutionLogEvent ended) {
    // A started run always has an end by now: either it finished or it was just marked aborted.
    if (started != null) {
      tracker.restore(
          started.executionId(),
          started.sessionId(),
          started.workflowId(),
          ended.status(),
          ended.nodeStatuses(),
          started.timestamp(),
          ended.timestamp());
    }
  }

  private static ExecutionLogEvent find(
      final List<ExecutionLogEvent> run, final ExecutionLogEvent.Type type) {
    return run.stream().filter(event -> event.type() == type).findFirst().orElse(null);
  }

  private static ExecutionLogEvent end(final List<ExecutionLogEvent> run) {
    final ExecutionLogEvent finished = find(run, ExecutionLogEvent.Type.RUN_FINISHED);
    return finished != null ? finished : find(run, ExecutionLogEvent.Type.RUN_ABORTED);
  }
}
