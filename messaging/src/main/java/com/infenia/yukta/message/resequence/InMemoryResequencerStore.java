/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.message.resequence;

import com.infenia.yukta.message.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** In-memory implementation of ResequencerStore. */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter"})
public class InMemoryResequencerStore implements ResequencerStore {

  /** Delay in milliseconds between cleanup operations. */
  private static final int CLEANUP_DELAY_MS = 100;

  /** Initial capacity for the resequencer store map. */
  private static final int INITIAL_CAPACITY = 16;

  /** Load factor for the LinkedHashMap backing the store. */
  private static final float LOAD_FACTOR = 0.75f;

  /** Thread-safe map storing sequence states indexed by key. */
  private final Map<String, SequenceState> store =
      Collections.synchronizedMap(new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true));

  /** Lock for synchronizing access to store during critical operations. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Reactive sink for broadcasting resequence results asynchronously. */
  private final Sinks.Many<ResequenceResult> asyncResults =
      Sinks.many().multicast().onBackpressureBuffer();

  /** Scheduled executor for periodic cleanup of expired sequences. */
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread thread = new Thread(r, "resequencer-store-cleanup");
            thread.setDaemon(true);
            return thread;
          });

  /** Default constructor. */
  public InMemoryResequencerStore() {
    super();
  }

  /** Initialize the cleanup scheduler. */
  @PostConstruct
  public void init() {
    scheduler.scheduleAtFixedRate(
        this::cleanup, CLEANUP_DELAY_MS, CLEANUP_DELAY_MS, TimeUnit.MILLISECONDS);
  }

  /** Shutdown the scheduler. */
  @PreDestroy
  public void shutdown() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (final InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public Mono<ResequenceResult> addMessage(
      final String key,
      final long sequenceNumber,
      final Message<?> message,
      final ResequenceConfig config) {
    return Mono.fromCallable(() -> processAdd(key, sequenceNumber, message, config));
  }

  private ResequenceResult processAdd(
      final String key,
      final long sequenceNumber,
      final Message<?> message,
      final ResequenceConfig config) {

    lock.lock();
    try {
      if (!store.containsKey(key) && store.size() >= config.maxPending()) {
        return new ResequenceResult(
            ResequenceResult.Status.OVERFLOW, List.of(message), key, "ResequencerStore overflow");
      }

      final SequenceState state = store.computeIfAbsent(key, k -> new SequenceState(config));

      if (sequenceNumber < state.nextExpected) {
        return new ResequenceResult(
            ResequenceResult.Status.LATE_ARRIVAL, List.of(message), key, "LATE_ARRIVAL");
      }

      if (state.buffer.containsKey(sequenceNumber)) {
        return new ResequenceResult(
            ResequenceResult.Status.DUPLICATE, List.of(message), key, "DUPLICATE_SEQUENCE_NUMBER");
      }

      state.buffer.put(sequenceNumber, message);
      state.lastAccessTime = System.currentTimeMillis();

      if (sequenceNumber == state.nextExpected) {
        final List<Message<?>> released = state.releaseConsecutive();
        return new ResequenceResult(ResequenceResult.Status.COMPLETED, released, key, null);
      }

      return new ResequenceResult(ResequenceResult.Status.WAITING, List.of(), key, null);

    } finally {
      lock.unlock();
    }
  }

  @Override
  public Flux<ResequenceResult> getAsyncResults() {
    return asyncResults.asFlux();
  }

  @Override
  public Flux<ResequenceResult> flushAll(final String keyPrefix) {
    lock.lock();
    try {
      final List<ResequenceResult> results = new ArrayList<>();
      final Iterator<Map.Entry<String, SequenceState>> iterator = store.entrySet().iterator();
      while (iterator.hasNext()) {
        final Map.Entry<String, SequenceState> entry = iterator.next();
        if (entry.getKey().startsWith(keyPrefix)) {
          final SequenceState state = entry.getValue();
          if (!state.buffer.isEmpty()) {
            results.add(
                new ResequenceResult(
                    ResequenceResult.Status.COMPLETED,
                    List.copyOf(state.buffer.values()),
                    entry.getKey(),
                    null));
          }
          iterator.remove();
        }
      }
      return Flux.fromIterable(results);
    } finally {
      lock.unlock();
    }
  }

  private void cleanup() {
    lock.lock();
    try {
      final List<ResequenceResult> timeoutResults = new ArrayList<>();
      final long now = System.currentTimeMillis();
      final Iterator<Map.Entry<String, SequenceState>> iterator = store.entrySet().iterator();
      while (iterator.hasNext()) {
        final Map.Entry<String, SequenceState> entry = iterator.next();
        final SequenceState state = entry.getValue();
        if (state.isExpired(now)) {
          handleExpiredState(iterator, entry, state, timeoutResults);
        }
      }
      timeoutResults.forEach(this::emitResult);
    } finally {
      lock.unlock();
    }
  }

  private void handleExpiredState(
      final Iterator<Map.Entry<String, SequenceState>> iterator,
      final Map.Entry<String, SequenceState> entry,
      final SequenceState state,
      final List<ResequenceResult> timeoutResults) {
    if (state.buffer.isEmpty()) {
      // Idle for too long with an empty buffer, purge the state
      iterator.remove();
    } else {
      // Gap timeout occurred: Jump to the first available message
      state.nextExpected = state.buffer.firstKey();
      final List<Message<?>> released = state.releaseConsecutive();
      timeoutResults.add(
          new ResequenceResult(
              ResequenceResult.Status.TIMEOUT_JUMP,
              released,
              entry.getKey(),
              "SEQUENCE_GAP_TIMEOUT"));
    }
  }

  private void emitResult(final ResequenceResult result) {
    Sinks.EmitResult emitRes = asyncResults.tryEmitNext(result);
    while (emitRes == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
      emitRes = asyncResults.tryEmitNext(result);
    }
  }

  /** Holds the state of an individual resequencing window. */
  private static class SequenceState {
    /** The next expected sequence number to release. */
    private long nextExpected;

    /** Timestamp of the last access or update. */
    private long lastAccessTime;

    /** Configuration settings for this sequence. */
    private final ResequenceConfig config;

    /** Ordered buffer of messages indexed by sequence number. */
    private final NavigableMap<Long, Message<?>> buffer = new TreeMap<>();

    /* default */ SequenceState(final ResequenceConfig config) {
      this.nextExpected = config.startIndex();
      this.config = config;
      this.lastAccessTime = System.currentTimeMillis();
    }

    /* default */ List<Message<?>> releaseConsecutive() {
      final List<Message<?>> released = new ArrayList<>();
      while (!buffer.isEmpty() && buffer.containsKey(nextExpected)) {
        released.add(buffer.remove(nextExpected));
        nextExpected++;
      }
      return released;
    }

    /* default */ boolean isExpired(final long now) {
      return now - lastAccessTime >= config.timeoutMs();
    }
  }
}
