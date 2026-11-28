// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.activity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The activity history in memory: the newest {@code capacity} entries, lost on a restart. */
public class InMemoryActivityStore implements ActivityStore {

  /** Entries kept by default. */
  public static final int DEFAULT_CAPACITY = 5_000;

  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();

  /** Oldest first; guarded by the lock. */
  private final Deque<ActivityEntry> entries = new ArrayDeque<>();

  private long last;

  /** Creates a store of the default size. */
  public InMemoryActivityStore() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates a store.
   *
   * @param capacity entries kept; the oldest go first
   */
  public InMemoryActivityStore(final int capacity) {
    this.capacity = capacity;
  }

  @Override
  public Mono<Void> append(final List<ActivityEntry> added) {
    return Mono.fromRunnable(() -> {
      lock.lock();
      try {
        for (final ActivityEntry e : added) {
          last++;
          entries.addLast(
              new ActivityEntry(last, e.at(), e.type(), e.severity(), e.executionId(), e.json()));
          if (entries.size() > capacity) {
            entries.removeFirst();
          }
        }
      } finally {
        lock.unlock();
      }
    });
  }

  @Override
  public Flux<ActivityEntry> newest(
      final Set<String> types,
      final Set<String> severities,
      @Nullable final Long before,
      final int limit) {
    return Flux.defer(() -> {
      lock.lock();
      try {
        return Flux.fromIterable(
            entries.reversed().stream()
                .filter(e -> before == null || e.seq() < before)
                .filter(e -> types.isEmpty() || types.contains(e.type()))
                .filter(e -> severities.isEmpty() || severities.contains(e.severity()))
                .limit(limit)
                .toList());
      } finally {
        lock.unlock();
      }
    });
  }
}
