// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spaces the calls to one provider evenly ({@code yukta.llm.rate-limit.<provider>} requests per
 * minute), across every node and run, so a burst of workflows cannot exceed the provider's quota. A
 * call waits for its turn; if the turn is further away than the call may take, it is refused.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // every access holds the lock
final class ProviderRateLimits {

  /** Guards both maps. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Nanoseconds between two calls, by provider name in lower case. */
  private Map<String, Long> spacing = Map.of();

  /** When the next call to the provider may start, in nanoseconds on the caller's clock. */
  private final Map<String, Long> nextFree = new HashMap<>();

  /** Creates a limiter with no limits. */
  /* default */ ProviderRateLimits() {
    super();
  }

  /**
   * Sets the limits.
   *
   * @param perMinute requests per minute by provider name; a value of 0 or less means no limit
   */
  /* default */ void configure(final Map<String, Integer> perMinute) {
    final Map<String, Long> next = new HashMap<>();
    perMinute.forEach(
        (provider, limit) -> {
          if (limit != null && limit > 0) {
            next.put(provider.toLowerCase(Locale.ROOT), TimeUnit.MINUTES.toNanos(1) / limit);
          }
        });
    lock.lock();
    try {
      spacing = next;
      nextFree.clear();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Takes the next turn for a provider.
   *
   * @param provider the provider name
   * @param now the current time in nanoseconds
   * @param maxWait the longest the call may wait
   * @return how long to wait before the call, in nanoseconds (0 if there is no limit or no wait)
   * @throws IllegalStateException if the turn is further away than {@code maxWait}
   */
  /* default */ long reserve(final String provider, final long now, final Duration maxWait) {
    final String name = provider.toLowerCase(Locale.ROOT);
    long wait = 0;
    lock.lock();
    try {
      final Long gap = spacing.get(name);
      if (gap != null) {
        final long start = Math.max(now, nextFree.getOrDefault(name, now));
        wait = start - now;
        if (wait > maxWait.toNanos()) {
          throw new IllegalStateException(
              "Rate limit of "
                  + provider
                  + " reached; the next call would wait "
                  + wait / 1_000_000L
                  + " ms");
        }
        nextFree.put(name, start + gap);
      }
    } finally {
      lock.unlock();
    }
    return wait;
  }
}
