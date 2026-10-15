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
  static List<ExecutionLogEvent> trim(
      final List<ExecutionLogEvent> events, final int retainedRuns) {
    final List<String> runs = runsByLastActivity(events);
    if (runs.size() <= retainedRuns) {
      return events;
    }
    final Set<String> keep =
        new LinkedHashSet<>(runs.subList(runs.size() - retainedRuns, runs.size()));
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
