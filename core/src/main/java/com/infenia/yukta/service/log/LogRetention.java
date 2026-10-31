// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** Decides which events of an execution log survive a compaction. */
@UtilityClass
class LogRetention {

  /**
   * Keeps the events of the newest runs by last activity, every run without an end, and the newest
   * scheduler changes (they belong to no run).
   *
   * @param events the whole log, oldest first
   * @param retainedRuns how many runs (and scheduler changes) to keep
   * @return the kept events, or {@code events} itself when nothing is dropped
   */
  /**
   * The newest runs of every session that are kept even when a busier session has pushed them out
   * of the global limit. ponytail: a constant; make it a setting if sessions need different values.
   */
  /* default */ static final int MIN_PER_SESSION = 10;

  /**
   * Which runs to keep: the newest {@code retainedRuns} overall, plus the newest {@link
   * #MIN_PER_SESSION} of each session, so one busy session cannot push out the others' history.
   *
   * @param runs the run ids, oldest first
   * @param sessionOf the session of a run
   * @param retainedRuns the global limit
   * @return the ids to keep
   */
  /* default */ static Set<String> keep(
      final List<String> runs,
      final java.util.function.Function<String, String> sessionOf,
      final int retainedRuns) {
    final Set<String> keep =
        new LinkedHashSet<>(runs.subList(Math.max(0, runs.size() - retainedRuns), runs.size()));
    // Half the global limit at most, so a small limit still means what it says for one session.
    final int floor = Math.min(MIN_PER_SESSION, retainedRuns / 2);
    final java.util.Map<String, Integer> perSession = new java.util.HashMap<>();
    for (final String run : runs.reversed()) {
      if (perSession.merge(String.valueOf(sessionOf.apply(run)), 1, Integer::sum) <= floor) {
        keep.add(run);
      }
    }
    return keep;
  }

  /* default */ static List<ExecutionLogEvent> trim(
      final List<ExecutionLogEvent> events, final int retainedRuns) {
    final List<String> runs = runsByLastActivity(events);
    final long scheduler =
        events.stream().filter(e -> ExecutionLogEvent.SCHEDULER.equals(e.executionId())).count();
    return runs.size() <= retainedRuns && scheduler <= retainedRuns
        ? events
        : dropOld(events, runs, retainedRuns);
  }

  private static List<ExecutionLogEvent> dropOld(
      final List<ExecutionLogEvent> events, final List<String> runs, final int retainedRuns) {
    final java.util.Map<String, String> sessions = new java.util.HashMap<>();
    events.stream()
        .filter(e -> e.sessionId() != null)
        .forEach(e -> sessions.putIfAbsent(e.executionId(), e.sessionId()));
    final Set<String> keep = keep(runs, sessions::get, retainedRuns);
    keep.addAll(openRuns(events));
    final List<ExecutionLogEvent> scheduler =
        events.stream().filter(e -> ExecutionLogEvent.SCHEDULER.equals(e.executionId())).toList();
    final Set<ExecutionLogEvent> keepScheduler =
        Set.copyOf(
            scheduler.subList(Math.max(0, scheduler.size() - retainedRuns), scheduler.size()));
    return events.stream()
        .filter(event -> keep.contains(event.executionId()) || keepScheduler.contains(event))
        .toList();
  }

  /** Run ids, the one with the oldest last event first; scheduler changes are not runs. */
  private static List<String> runsByLastActivity(final List<ExecutionLogEvent> events) {
    final Set<String> runs = new LinkedHashSet<>();
    for (final ExecutionLogEvent event : events) {
      if (!ExecutionLogEvent.SCHEDULER.equals(event.executionId())) {
        runs.remove(event.executionId());
        runs.add(event.executionId());
      }
    }
    return new ArrayList<>(runs);
  }

  /** Runs that started and have neither finished nor been marked aborted. */
  private static Set<String> openRuns(final List<ExecutionLogEvent> events) {
    final Set<String> open = new LinkedHashSet<>();
    for (final ExecutionLogEvent event : events) {
      switch (event.type()) {
        case RUN_STARTED -> open.add(event.executionId());
        case RUN_FINISHED, RUN_ABORTED -> open.remove(event.executionId());
        default -> {
          // not a start or an end
        }
      }
    }
    return open;
  }
}
