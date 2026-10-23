// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts requests per caller in fixed one-minute windows, so an agent stuck in a retry loop cannot
 * start runs or read logs without bound. A limit of 0 turns it off.
 */
public final class CallRateLimiter {

  /** Length of one window, in nanoseconds. */
  private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

  /** Requests allowed per caller and window; 0 means unlimited. */
  private final int perMinute;

  /** Reads the time in nanoseconds. */
  private final LongSupplier nanos;

  /** Window start and count per caller. */
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  /** One caller's current window. */
  private static final class Window {
    /** Start of the window. */
    private long start;

    /** Requests seen in it. */
    private int count;
  }

  /**
   * Create a limiter.
   *
   * @param perMinute requests per caller and minute; 0 or less means unlimited
   */
  public CallRateLimiter(final int perMinute) {
    this(perMinute, System::nanoTime);
  }

  /* default */ CallRateLimiter(final int perMinute, final LongSupplier nanos) {
    this.perMinute = perMinute;
    this.nanos = nanos;
  }

  /**
   * Count one request.
   *
   * @param caller who makes it (the key name)
   * @return true if it is within the limit
   */
  public boolean tryAcquire(final String caller) {
    return perMinute <= 0 || count(caller) <= perMinute;
  }

  /** Counts the request in the caller's window; compute() makes the update atomic per caller. */
  private int count(final String caller) {
    final long now = nanos.getAsLong();
    return windows.compute(
            caller,
            (_, old) -> {
              final Window window = old == null ? new Window() : old;
              if (old == null || now - window.start >= WINDOW_NANOS) {
                window.start = now;
                window.count = 0;
              }
              window.count++;
              return window;
            })
        .count;
  }
}
