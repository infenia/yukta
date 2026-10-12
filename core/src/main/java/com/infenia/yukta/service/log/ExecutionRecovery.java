// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Holds the recorded outputs of runs, which go with the runs that are dropped. */
  private final NodeCheckpointStore checkpoints;

  /**
   * Creates the recovery step.
   *
   * @param store the execution log
   * @param tracker receives the restored runs
   * @param retainedRuns number of most recent runs kept
   * @param checkpoints recorded outputs, cleared for runs that are dropped
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public ExecutionRecovery(
      final ExecutionLogStore store,
      final DefaultTaskTrackerService tracker,
      @Value("${yukta.execution-log.retained-runs:500}") final int retainedRuns,
      final NodeCheckpointStore checkpoints) {
    this.store = store;
    this.tracker = tracker;
    this.retainedRuns = Math.max(1, retainedRuns);
    this.checkpoints = checkpoints;
  }

  /* default */ ExecutionRecovery(
      final ExecutionLogStore store,
      final DefaultTaskTrackerService tracker,
      final int retainedRuns) {
    this(store, tracker, retainedRuns, new InMemoryNodeCheckpointStore());
  }

  @Override
  public void afterSingletonsInstantiated() {
    recover();
  }

  /** Marks interrupted runs aborted, trims the log and restores recent runs into the tracker. */
  public void recover() {
    final Map<String, List<ExecutionLogEvent>> byRun = new LinkedHashMap<>();
    final List<ExecutionLogEvent> scheduler = new ArrayList<>();
    for (final ExecutionLogEvent event : store.readAll()) {
      if (ExecutionLogEvent.SCHEDULER.equals(event.executionId())) {
        scheduler.add(event);
      } else {
        // Re-inserted on every event, so runs are ordered by their last activity: a long run
        // that was still going is newer than the short ones that started after it.
        final List<ExecutionLogEvent> run = byRun.remove(event.executionId());
        final List<ExecutionLogEvent> events = run == null ? new ArrayList<>() : run;
        events.add(event);
        byRun.put(event.executionId(), events);
      }
    }

    // Changes that belong to no run are not runs: they stay, the newest few.
    final List<ExecutionLogEvent> kept =
        new ArrayList<>(
            scheduler.subList(Math.max(0, scheduler.size() - retainedRuns), scheduler.size()));
    final List<List<ExecutionLogEvent>> runs = new ArrayList<>(byRun.values());
    final int skip = Math.max(0, runs.size() - retainedRuns);
    runs.subList(0, skip).forEach(run -> checkpoints.clear(run.getFirst().executionId()));
    int aborted = 0;
    for (final List<ExecutionLogEvent> run : runs.subList(skip, runs.size())) {
      final ExecutionLogEvent started = find(run, ExecutionLogEvent.Type.RUN_STARTED);
      final ExecutionLogEvent ended = end(run);
      if (started != null && ended == null) {
        run.add(ExecutionLogEvent.aborted(started, finishedNodes(run)));
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
          ended.timestamp(),
          started.definitionVersion());
    }
  }

  private static Map<String, String> finishedNodes(final List<ExecutionLogEvent> run) {
    final Map<String, String> nodes = new LinkedHashMap<>();
    run.stream()
        .filter(event -> event.type() == ExecutionLogEvent.Type.NODE_FINISHED)
        .forEach(event -> nodes.put(event.nodeId(), event.status()));
    return nodes;
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
