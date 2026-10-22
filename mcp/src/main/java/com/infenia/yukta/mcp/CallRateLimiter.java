// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts MCP requests per caller in fixed one-minute windows, so an agent stuck in a retry loop
 * cannot start runs or read logs without bound. A limit of 0 turns it off.
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
    if (perMinute <= 0) {
      return true;
    }
    final long now = nanos.getAsLong();
    final Window window = windows.computeIfAbsent(caller, _ -> new Window());
    synchronized (window) {
      if (now - window.start >= WINDOW_NANOS) {
        window.start = now;
        window.count = 0;
      }
      window.count++;
      return window.count <= perMinute;
    }
  }
}
