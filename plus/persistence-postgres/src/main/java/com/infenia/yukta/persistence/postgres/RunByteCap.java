// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cap on the JSON text one run may record, counted by this server. ponytail: per process, not
 * shared by servers.
 */
final class RunByteCap {

  /** Most JSON text one run may record. */
  private final long maxBytesPerRun;

  /** JSON text recorded per run by this server. */
  private final Map<String, AtomicLong> bytesByRun = new ConcurrentHashMap<>();

  /** The nodes already marked incomplete for the cap ({@code run \0 node}), so it is done once. */
  private final Set<String> capped = ConcurrentHashMap.newKeySet();

  /**
   * Creates the cap.
   *
   * @param maxBytesPerRun most JSON text one run may record, at least 1
   */
  /* default */ RunByteCap(final long maxBytesPerRun) {
    this.maxBytesPerRun = Math.max(1, maxBytesPerRun);
  }

  /** The most JSON text one run may record. */
  /* default */ long max() {
    return maxBytesPerRun;
  }

  /** Counts the batch against the run's cap; a batch that does not fit is not counted. */
  /* default */ boolean fits(final String executionId, final List<String> json) {
    final long size = json.stream().mapToLong(String::length).sum();
    final AtomicLong used = bytesByRun.computeIfAbsent(executionId, _ -> new AtomicLong());
    if (used.addAndGet(size) <= maxBytesPerRun) {
      return true;
    }
    used.addAndGet(-size);
    return false;
  }

  /** True the first time it is asked for a node of a run, so the node is marked incomplete once. */
  /* default */ boolean firstCapped(final String executionId, final String nodeId) {
    return capped.add(executionId + '\0' + nodeId);
  }

  /** Drops the cap counters of the given runs, or of every run but them. */
  /* default */ void forget(final Set<String> runs, final boolean allBut) {
    bytesByRun.keySet().removeIf(run -> runs.contains(run) != allBut);
    capped.removeIf(key -> runs.contains(key.substring(0, key.indexOf('\0'))) != allBut);
  }
}
