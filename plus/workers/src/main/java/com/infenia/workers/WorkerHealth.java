// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Keeps a worker that keeps failing out of the picks for a while. A worker that fails fast has the
 * most free slots, so without this it would attract every attempt of its pool ("black hole"). A
 * worker is quarantined only after {@link #FAILURES} attempts in a row failed on it while another
 * worker ran the same plugin type successfully since the first of them: when every worker fails
 * (the database the nodes call is down), nobody is to blame and nobody is quarantined.
 */
final class WorkerHealth {

  /** Attempts in a row that must fail on a worker before it is quarantined. */
  /* default */ static final int FAILURES = 5;

  /** How long a quarantined worker gets no attempts. */
  // ponytail: fixed; make it a setting when a fleet needs another value
  /* default */ static final Duration COOL_DOWN = Duration.ofSeconds(60);

  /** Nanoseconds now. */
  private final LongSupplier clock;

  /** The failure streak of each worker that has one. */
  private final Map<String, Streak> streaks = new ConcurrentHashMap<>();

  /** Until when each quarantined worker is skipped. */
  private final Map<String, Long> quarantinedUntil = new ConcurrentHashMap<>();

  /** Per plugin type, when each worker last ran an attempt of it successfully. */
  private final Map<String, Map<String, Long>> lastSuccess = new ConcurrentHashMap<>();

  /**
   * Creates the record.
   *
   * @param clock nanoseconds now ({@code System::nanoTime})
   */
  /* default */ WorkerHealth(final LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * An attempt succeeded on the worker: its streak ends.
   *
   * @param workerId the worker
   * @param pluginType the plugin type of the attempt
   */
  /* default */ void succeeded(final String workerId, final String pluginType) {
    streaks.remove(workerId);
    lastSuccess
        .computeIfAbsent(pluginType, _ -> new ConcurrentHashMap<>())
        .put(workerId, clock.getAsLong());
  }

  /**
   * An attempt failed on the worker in a way that may be the worker's fault (it was lost, or the
   * plugin failed there).
   *
   * @param workerId the worker
   * @param pluginType the plugin type of the attempt
   * @return true if the worker is quarantined now
   */
  /* default */ boolean failed(final String workerId, final String pluginType) {
    final long now = clock.getAsLong();
    final Streak streak =
        streaks.merge(
            workerId, new Streak(1, now), (old, _) -> new Streak(old.failures() + 1, old.since()));
    final boolean quarantine =
        streak.failures() >= FAILURES && othersSucceeded(workerId, pluginType, streak.since());
    if (quarantine) {
      streaks.remove(workerId);
      quarantinedUntil.put(workerId, now + COOL_DOWN.toNanos());
    }
    return quarantine;
  }

  /**
   * Whether the worker is quarantined now.
   *
   * @param workerId the worker
   * @return true while its cool-down runs
   */
  /* default */ boolean quarantined(final String workerId) {
    final Long until = quarantinedUntil.get(workerId);
    final boolean skipped = until != null && clock.getAsLong() - until < 0;
    if (until != null && !skipped) {
      quarantinedUntil.remove(workerId, until);
    }
    return skipped;
  }

  private boolean othersSucceeded(
      final String workerId, final String pluginType, final long since) {
    return lastSuccess.getOrDefault(pluginType, Map.of()).entrySet().stream()
        .anyMatch(entry -> !entry.getKey().equals(workerId) && entry.getValue() - since >= 0);
  }

  /** The current failures in a row of a worker, and when the first of them happened. */
  private record Streak(int failures, long since) {}
}
