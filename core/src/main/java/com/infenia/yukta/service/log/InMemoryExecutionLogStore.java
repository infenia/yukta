// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default execution log: kept in memory, so nothing survives a restart. The PostgreSQL module
 * ({@code yukta.session.store-type=postgres}) keeps it durable.
 *
 * <p>It is trimmed to the newest {@code retained-runs} runs after every {@code compact-after}
 * appended events; a run without an end is never dropped.
 */
public class InMemoryExecutionLogStore implements ExecutionLogStore {

  /** Runs that survive a trim. */
  private final int retainedRuns;

  /** Appended events after which the log is trimmed. */
  private final int compactAfter;

  /** Guards {@link #events} and {@link #sinceTrim}. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The events, oldest first. */
  private List<ExecutionLogEvent> events = new ArrayList<>();

  /** The same events by execution id, so a lookup of one run does not scan the log. */
  @SuppressWarnings("PMD.UseConcurrentHashMap") // guarded by the lock
  private Map<String, List<ExecutionLogEvent>> byRun = new HashMap<>();

  /** Events appended since the last trim. */
  private int sinceTrim;

  /** Creates a store with the default limits. */
  public InMemoryExecutionLogStore() {
    this(500, 10_000);
  }

  /**
   * Creates a store.
   *
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   */
  public InMemoryExecutionLogStore(final int retainedRuns, final int compactAfter) {
    this.retainedRuns = Math.max(1, retainedRuns);
    this.compactAfter = Math.max(1, compactAfter);
  }

  @Override
  public void append(final ExecutionLogEvent event) {
    lock.lock();
    try {
      events.add(event);
      byRun.computeIfAbsent(event.executionId(), _ -> new ArrayList<>()).add(event);
      sinceTrim++;
      if (sinceTrim >= compactAfter) {
        sinceTrim = 0;
        events = new ArrayList<>(LogRetention.trim(events, retainedRuns));
        reindex();
      }
    } finally {
      lock.unlock();
    }
  }

  // Memory only: nothing here waits, so the reads are plain snapshots taken on subscription.
  @Override
  public Flux<ExecutionLogEvent> readAll() {
    return Flux.defer(() -> {
      lock.lock();
      try {
        return Flux.fromIterable(List.copyOf(events));
      } finally {
        lock.unlock();
      }
    });
  }

  @Override
  public Mono<Void> applyRecovery(
      final Set<String> dropped, final int schedulerKept, final List<ExecutionLogEvent> added) {
    return Mono.fromRunnable(() -> {
      lock.lock();
      try {
        int scheduler = 0;
        for (final ExecutionLogEvent event : events) {
          if (ExecutionLogEvent.SCHEDULER.equals(event.executionId())) {
            scheduler++;
          }
        }
        int skip = Math.max(0, scheduler - schedulerKept);
        final List<ExecutionLogEvent> kept = new ArrayList<>(events.size() + added.size());
        for (final ExecutionLogEvent event : events) {
          if (ExecutionLogEvent.SCHEDULER.equals(event.executionId()) && skip > 0) {
            skip--;
          } else if (!dropped.contains(event.executionId())) {
            kept.add(event);
          }
        }
        kept.addAll(added);
        events = kept;
        reindex();
        sinceTrim = 0;
      } finally {
        lock.unlock();
      }
    });
  }

  @Override
  public Flux<ExecutionLogEvent> forExecution(final String executionId) {
    return Flux.defer(() -> {
      lock.lock();
      try {
        return Flux.fromIterable(List.copyOf(byRun.getOrDefault(executionId, List.of())));
      } finally {
        lock.unlock();
      }
    });
  }

  @Override
  public Flux<ExecutionLogEvent> newest(
      final Set<ExecutionLogEvent.Type> types, final String executionId, final int limit) {
    return Flux.defer(() -> {
      lock.lock();
      try {
        final List<ExecutionLogEvent> source =
            executionId == null ? events : byRun.getOrDefault(executionId, List.of());
        final List<ExecutionLogEvent> found = new ArrayList<>();
        for (int i = source.size() - 1; i >= 0 && found.size() < limit; i--) {
          if (ExecutionLogStore.matches(source.get(i), types, executionId)) {
            found.add(source.get(i));
          }
        }
        return Flux.fromIterable(found);
      } finally {
        lock.unlock();
      }
    });
  }

  /** Rebuilds the per-run index; the caller holds the lock. */
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private void reindex() {
    byRun = new HashMap<>();
    for (final ExecutionLogEvent event : events) {
      byRun.computeIfAbsent(event.executionId(), _ -> new ArrayList<>()).add(event);
    }
  }
}
