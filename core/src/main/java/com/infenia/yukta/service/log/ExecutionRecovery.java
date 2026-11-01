// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Told which runs a recovery found interrupted; used to resume them on their own. */
  private Consumer<List<String>> afterRecovery = _ -> {};

  /** Tells whether recovery happens at startup or on takeover; absent means at startup. */
  private LeaderElection election;

  /** Gets back the limits that were last changed at run time, when present. */
  private ExecutionScheduler executionScheduler;

  /**
   * Creates the recovery step.
   *
   * @param store the execution log
   * @param tracker receives the restored runs
   * @param properties the {@code yukta.execution-log} settings: runs kept
   * @param checkpoints recorded outputs, cleared for runs that are dropped
   */
  @Autowired
  public ExecutionRecovery(
      final ExecutionLogStore store,
      final DefaultTaskTrackerService tracker,
      final YuktaProperties properties,
      final NodeCheckpointStore checkpoints) {
    this(store, tracker, properties.executionLog().retainedRuns(), checkpoints);
  }

  /* default */ ExecutionRecovery(
      final ExecutionLogStore store,
      final DefaultTaskTrackerService tracker,
      final int retainedRuns,
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

  /**
   * Asks to be told, after every recovery, which runs it found interrupted.
   *
   * @param listener receives the ids of the runs that are now marked aborted
   */
  public void onRecovered(final Consumer<List<String>> listener) {
    this.afterRecovery = listener;
  }

  /**
   * Lets the election decide when recovery runs.
   *
   * @param leaderElection the election
   */
  @Autowired(required = false)
  public void setLeaderElection(final LeaderElection leaderElection) {
    this.election = leaderElection;
  }

  /**
   * Lets recovery re-apply the newest run-time limit change found in the log.
   *
   * @param scheduler the scheduler
   */
  @Autowired(required = false)
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public void setExecutionScheduler(final ExecutionScheduler scheduler) {
    this.executionScheduler = scheduler;
  }

  @Override
  public void afterSingletonsInstantiated() {
    // With several servers a standby must not mark a living leader's runs aborted: recovery then
    // happens on takeover (TakeoverRecovery).
    if (election == null || !election.recoversOnTakeover()) {
      recover();
    }
  }

  /**
   * Marks interrupted runs aborted, trims the log and restores recent runs into the tracker.
   *
   * @return the ids of the runs that were interrupted and are now marked aborted
   */
  public List<String> recover() {
    final List<ExecutionLogEvent> all = store.readAll();
    final List<ExecutionLogEvent> scheduler =
        all.stream().filter(e -> ExecutionLogEvent.SCHEDULER.equals(e.executionId())).toList();
    final Map<String, List<ExecutionLogEvent>> byRun = byRun(all);

    restoreLimits(scheduler);
    // Changes that belong to no run are not runs: they stay, the newest few.
    final List<ExecutionLogEvent> kept =
        new ArrayList<>(
            scheduler.subList(Math.max(0, scheduler.size() - retainedRuns), scheduler.size()));
    final List<List<ExecutionLogEvent>> runs = new ArrayList<>(byRun.values());
    final Set<String> keep =
        LogRetention.keep(
            new ArrayList<>(byRun.keySet()),
            id -> {
              final ExecutionLogEvent first =
                  find(byRun.get(id), ExecutionLogEvent.Type.RUN_STARTED);
              return first == null ? null : first.sessionId();
            },
            retainedRuns);
    final List<String> abortedIds = new ArrayList<>();
    int skip = 0;
    for (final List<ExecutionLogEvent> run : runs) {
      if (!keep.contains(run.getFirst().executionId())) {
        checkpoints.clear(run.getFirst().executionId());
        skip++;
        continue;
      }
      final ExecutionLogEvent started = find(run, ExecutionLogEvent.Type.RUN_STARTED);
      final ExecutionLogEvent ended = end(run);
      if (started != null && ended == null) {
        run.add(ExecutionLogEvent.aborted(started, finishedNodes(run)));
        abortedIds.add(started.executionId());
      }
      kept.addAll(run);
      restore(started, end(run));
    }
    store.replaceAll(kept);
    log.atInfo()
        .addKeyValue("restored", runs.size() - skip)
        .addKeyValue("aborted", abortedIds.size())
        .addKeyValue("dropped", skip)
        .log("Recovered execution history");
    afterRecovery.accept(abortedIds);
    return abortedIds;
  }

  /**
   * Groups the events of each run, ordered by the run's last activity: a run is re-inserted on
   * every event, so a long run that was still going is newer than the short ones that started after
   * it. Scheduler changes belong to no run and are left out.
   */
  private static Map<String, List<ExecutionLogEvent>> byRun(final List<ExecutionLogEvent> events) {
    final Map<String, List<ExecutionLogEvent>> byRun = new LinkedHashMap<>();
    for (final ExecutionLogEvent event : events) {
      if (!ExecutionLogEvent.SCHEDULER.equals(event.executionId())) {
        final List<ExecutionLogEvent> run = byRun.remove(event.executionId());
        final List<ExecutionLogEvent> runEvents = run == null ? new ArrayList<>() : run;
        runEvents.add(event);
        byRun.put(event.executionId(), runEvents);
      }
    }
    return byRun;
  }

  /**
   * Re-applies the newest limit change that was applied (not refused), so the limits an operator
   * set at run time survive a restart; the log wins over the configuration from then on.
   */
  private void restoreLimits(final List<ExecutionLogEvent> schedulerEvents) {
    final ExecutionScheduler target = executionScheduler;
    if (target == null) {
      return;
    }
    for (final ExecutionLogEvent event : schedulerEvents.reversed()) {
      if (event.type() == ExecutionLogEvent.Type.LIMITS_CHANGED) {
        target.setLimits(
            Integer.parseInt(event.nodeStatuses().get(ExecutionLogEvent.MAX_CONCURRENT)),
            Integer.parseInt(event.nodeStatuses().get(ExecutionLogEvent.MAX_PER_WORKFLOW)));
        log.atInfo()
            .addKeyValue("limits", event.nodeStatuses())
            .log("Restored the limits changed at run time");
        return;
      }
    }
  }

  private void restore(final ExecutionLogEvent started, final ExecutionLogEvent ended) {
    // A started run always has an end by now: either it finished or it was just marked aborted.
    if (started != null) {
      tracker.restore(
          started.executionId(),
          started.sessionId(),
          started.workflowId(),
          TaskStatus.valueOf(ended.status()),
          statuses(ended.nodeStatuses()),
          started.timestamp(),
          ended.timestamp(),
          started.definitionVersion(),
          started.initiator());
    }
  }

  private static Map<String, TaskStatus> statuses(final Map<String, String> names) {
    final Map<String, TaskStatus> statuses = new LinkedHashMap<>();
    names.forEach((nodeId, name) -> statuses.put(nodeId, TaskStatus.valueOf(name)));
    return statuses;
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
