// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Counts requests per caller over a sliding minute (the previous minute counts for the part of it
 * that is still inside the window), so an agent stuck in a retry loop cannot start runs or read
 * logs without bound and cannot get twice the limit across a minute boundary. A limit of 0 turns it
 * off.
 */
public final class CallRateLimiter {

  /** Length of one window, in nanoseconds. */
  private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

  /** Callers remembered; the least recently used go first. */
  private static final int MAX_CALLERS = 10_000;

  /** Requests allowed per caller and minute; 0 means unlimited. */
  private final int perMinute;

  /** Reads the time in nanoseconds. */
  private final LongSupplier nanos;

  /** Counts per caller; a caller idle for two windows is forgotten. */
  private final Cache<String, Window> windows =
      Caffeine.newBuilder()
          .maximumSize(MAX_CALLERS)
          .expireAfterAccess(Duration.ofNanos(2 * WINDOW_NANOS))
          .build();

  /** One caller's current and previous window. */
  private static final class Window {
    private final ReentrantLock lock = new ReentrantLock();

    /** Start of the current window. */
    private long start;

    /** Requests in the current window. */
    private int count;

    /** Requests in the window before. */
    private int previous;

    private Window(final long start) {
      this.start = start;
    }

    /** Moves to the window that holds {@code now}. */
    private void roll(final long now) {
      final long elapsed = now - start;
      if (elapsed >= 2 * WINDOW_NANOS) {
        previous = 0;
        count = 0;
        start = now;
      } else if (elapsed >= WINDOW_NANOS) {
        previous = count;
        count = 0;
        start += WINDOW_NANOS;
      }
    }

    /** Requests in the minute that ends now. */
    private long weighted(final long now) {
      final double outside = (double) (now - start) / WINDOW_NANOS;
      return (long) Math.ceil(previous * (1 - outside)) + count;
    }

    private long hit(final long now) {
      lock.lock();
      try {
        roll(now);
        count++;
        return weighted(now);
      } finally {
        lock.unlock();
      }
    }

    private long seen(final long now) {
      lock.lock();
      try {
        roll(now);
        return weighted(now);
      } finally {
        lock.unlock();
      }
    }
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
    return windows.get(caller, _ -> new Window(now)).hit(now) <= perMinute;
  }

  /**
   * Whether the caller has used up its window, without counting anything.
   *
   * @param caller who is asking
   * @return true if the caller is at or over the limit
   */
  public boolean exceeded(final String caller) {
    final Window window = windows.getIfPresent(caller);
    return perMinute > 0 && window != null && window.seen(nanos.getAsLong()) >= perMinute;
  }
}
