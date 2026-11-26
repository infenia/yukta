// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admissions per throttle key ({@code admission.throttle}) over a sliding period. Not thread safe:
 * the scheduler calls it under its lock. Times are {@link System#nanoTime()} values.
 */
final class ThrottleWindows {

  /**
   * Every so many admissions, windows that have emptied are dropped, so idle keys do not pile up.
   */
  private static final int SWEEP_EVERY = 1024;

  /** Windows by key. */
  private final Map<Object, Window> windows = new ConcurrentHashMap<>();

  /** Admissions since the last sweep. */
  private int sinceSweep;

  /**
   * Whether one more start with the key fits its throttle now.
   *
   * @param key the key
   * @param limit admissions per period
   * @param now the time
   * @return true if it fits
   */
  /* default */ boolean fits(final Object key, final int limit, final long now) {
    return waitNanos(key, limit, now) == 0;
  }

  /**
   * How long until one more start with the key fits.
   *
   * @param key the key
   * @param limit admissions per period
   * @param now the time
   * @return nanoseconds to wait, 0 if it fits now
   */
  /* default */ long waitNanos(final Object key, final int limit, final long now) {
    final Window window = windows.get(key);
    if (window == null) {
      return 0;
    }
    prune(window, now);
    final Deque<Long> admitted = window.admitted();
    if (admitted.size() < limit) {
      return 0;
    }
    // the admission that has to leave the period first is the one `limit` places from the newest
    final long leaving = admitted.stream().skip(admitted.size() - limit).findFirst().orElse(now);
    return Math.max(1, leaving + window.periodNanos() - now);
  }

  /**
   * Records an admission.
   *
   * @param key the key
   * @param periodNanos the key's period
   * @param now the time
   */
  /* default */ void take(final Object key, final long periodNanos, final long now) {
    // ponytail: a changed period applies once the old window has emptied and been swept
    windows
        .computeIfAbsent(key, _ -> new Window(periodNanos, new ArrayDeque<>()))
        .admitted()
        .addLast(now);
    sinceSweep++;
    if (sinceSweep >= SWEEP_EVERY) {
      sinceSweep = 0;
      windows
          .values()
          .removeIf(window -> {
            prune(window, now);
            return window.admitted().isEmpty();
          });
    }
  }

  /**
   * How many keys have a window.
   *
   * @return the count
   */
  /* default */ int size() {
    return windows.size();
  }

  private static void prune(final Window window, final long now) {
    while (!window.admitted().isEmpty()
        && now - window.admitted().peekFirst() >= window.periodNanos()) {
      window.admitted().removeFirst();
    }
  }

  /** The admissions of one key that are still inside its period. */
  private record Window(long periodNanos, Deque<Long> admitted) {}
}
