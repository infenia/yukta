// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Lets a start that repeats inside a window share the first run: an agent that retries a call gets
 * the run it already started, kept in memory only.
 */
final class AutoKeyWindow {

  private final Duration window;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  private record Entry(Mono<String> execution, Instant until) {}

  /**
   * Creates the window.
   *
   * @param window how long a start stands in for its repeats; zero turns it off
   */
  /* default */ AutoKeyWindow(final Duration window) {
    this.window = window;
  }

  /**
   * Starts once per scope within the window.
   *
   * @param scope what repeats are matched on
   * @param start starts a new execution and returns its id
   * @return the id of the first start inside the window, or of a new one
   */
  /* default */ Mono<String> once(final String scope, final Supplier<Mono<String>> start) {
    if (window.isZero() || window.isNegative()) {
      return start.get();
    }
    final Instant now = Instant.now();
    entries.values().removeIf(entry -> !entry.until().isAfter(now));
    final Entry entry =
        entries.computeIfAbsent(scope, _ -> new Entry(start.get().cache(), now.plus(window)));
    // A failed start is not remembered, so the caller can retry.
    return entry.execution().doOnError(_ -> entries.remove(scope, entry));
  }
}
