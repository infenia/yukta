// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spaces the calls to one provider evenly ({@code yukta.llm.rate-limit.<provider>} requests per
 * minute), across every node and run, so a burst of workflows cannot exceed the provider's quota. A
 * call waits for its turn; if the turn is further away than the call may take, it is refused.
 */
final class ProviderRateLimits {

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
  /* default */ synchronized void configure(final Map<String, Integer> perMinute) {
    final Map<String, Long> next = new HashMap<>();
    perMinute.forEach(
        (provider, limit) -> {
          if (limit != null && limit > 0) {
            next.put(provider.toLowerCase(Locale.ROOT), TimeUnit.MINUTES.toNanos(1) / limit);
          }
        });
    spacing = next;
    nextFree.clear();
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
  /* default */ synchronized long reserve(
      final String provider, final long now, final Duration maxWait) {
    final String name = provider.toLowerCase(Locale.ROOT);
    final Long gap = spacing.get(name);
    if (gap == null) {
      return 0;
    }
    final long start = Math.max(now, nextFree.getOrDefault(name, now));
    final long wait = start - now;
    if (wait > maxWait.toNanos()) {
      throw new IllegalStateException(
          "Rate limit of "
              + provider
              + " reached; the next call would wait "
              + wait / 1_000_000L
              + " ms");
    }
    nextFree.put(name, start + gap);
    return wait;
  }
}
