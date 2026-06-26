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
package com.infenia.yukta.message.aggregate;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.util.SpelUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** In-memory implementation of AggregateStore using LinkedHashMap for LRU. */
@Slf4j
@Component
@SuppressWarnings("PMD.OnlyOneReturn")
public class InMemoryAggregateStore implements AggregateStore {

  /** Delay in milliseconds between cleanup operations. */
  private static final int CLEANUP_DELAY_MS = 100;

  /** Initial capacity for the aggregate store map. */
  private static final int INITIAL_CAPACITY = 16;

  /** Load factor for the LinkedHashMap backing the store. */
  private static final float LOAD_FACTOR = 0.75f;

  /** Window type constant for time-based aggregation. */
  private static final String TYPE_TIME = "TIME";

  /** Window type constant for session-based aggregation. */
  private static final String TYPE_SESSION = "SESSION";

  /** Window type constant for count-based aggregation. */
  private static final String TYPE_COUNT = "COUNT";

  /** Aggregation type constant for sum aggregation. */
  private static final String AGG_SUM = "SUM";

  /** Aggregation type constant for average aggregation. */
  private static final String AGG_AVERAGE = "AVERAGE";

  /** Aggregation type constant for minimum value aggregation. */
  private static final String AGG_MIN = "MIN";

  /** Aggregation type constant for maximum value aggregation. */
  private static final String AGG_MAX = "MAX";

  /** Aggregation type constant for list collection. */
  private static final String AGG_LIST = "COLLECT_LIST";

  /** Aggregation type constant for first value aggregation. */
  private static final String AGG_FIRST = "FIRST";

  /** Aggregation type constant for last value aggregation. */
  private static final String AGG_LAST = "LAST";

  /** Aggregation type constant for custom aggregation. */
  private static final String AGG_CUSTOM = "CUSTOM";

  /** Null policy constant to replace nulls with zero. */
  private static final String POLICY_ZERO = "ZERO";

  /** Null policy constant to fail on null values. */
  private static final String POLICY_FAIL = "FAIL";

  /** Thread-safe map storing aggregate states indexed by key. */
  private final Map<String, AggregateState> store =
      Collections.synchronizedMap(new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR, true));

  /** Lock for synchronizing access to store during critical operations. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Reactive sink for broadcasting aggregate results asynchronously. */
  private final Sinks.Many<AggregateResult> asyncResults =
      Sinks.many().multicast().onBackpressureBuffer();

  /** Scheduled executor for periodic cleanup of expired aggregates. */
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final Thread thread = new Thread(r, "aggregate-store-cleanup");
            thread.setDaemon(true);
            return thread;
          });

  /** Default constructor. */
  public InMemoryAggregateStore() {
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
  public Mono<AggregateResult> addValue(
      final String key,
      final Object value,
      final Message<?> message,
      final AggregateConfig config) {
    return Mono.fromCallable(() -> processAdd(key, value, message, config));
  }

  private AggregateResult processAdd(
      final String key,
      final Object value,
      final Message<?> message,
      final AggregateConfig config) {

    AggregateResult evictionResult = null;
    lock.lock();
    try {
      if (!store.containsKey(key) && store.size() >= config.maxPendingWindows()) {
        final Iterator<Map.Entry<String, AggregateState>> iterator = store.entrySet().iterator();
        if (iterator.hasNext()) {
          final Map.Entry<String, AggregateState> entry = iterator.next();
          iterator.remove();
          final AggregateState oldest = entry.getValue();
          evictionResult =
              new AggregateResult(
                  AggregateResult.Status.EVICTED,
                  oldest.getFinalResult(),
                  entry.getKey(),
                  oldest.getLastMessage());
        }
      }
    } finally {
      lock.unlock();
    }

    if (evictionResult != null) {
      asyncResults.tryEmitNext(evictionResult);
    }

    final AggregateState state =
        store.compute(
            key,
            (k, existing) -> {
              AggregateState targetState = existing;
              if (targetState == null) {
                targetState = new AggregateState(message, config);
              }
              targetState.update(value, message);
              return targetState;
            });

    if (state.isTriggered()) {
      store.remove(key);
      return new AggregateResult(
          AggregateResult.Status.COMPLETED, state.getFinalResult(), key, state.getLastMessage());
    }

    return new AggregateResult(AggregateResult.Status.WAITING, null, key, message);
  }

  @Override
  public Mono<AggregateResult> flush(final String key) {
    return Mono.fromCallable(
        () -> {
          final AggregateState state = store.remove(key);
          if (state == null) {
            return null;
          }
          return new AggregateResult(
              AggregateResult.Status.COMPLETED,
              state.getFinalResult(),
              key,
              state.getLastMessage());
        });
  }

  @Override
  public Flux<AggregateResult> flushAll() {
    return flushAll("");
  }

  @Override
  public Flux<AggregateResult> flushAll(final String keyPrefix) {
    final List<AggregateResult> results = new ArrayList<>();
    lock.lock();
    try {
      final Iterator<Map.Entry<String, AggregateState>> iterator = store.entrySet().iterator();
      while (iterator.hasNext()) {
        final Map.Entry<String, AggregateState> entry = iterator.next();
        if (entry.getKey().startsWith(keyPrefix)) {
          final AggregateState state = entry.getValue();
          results.add(
              new AggregateResult(
                  AggregateResult.Status.COMPLETED,
                  state.getFinalResult(),
                  entry.getKey(),
                  state.getLastMessage()));
          iterator.remove();
        }
      }
    } finally {
      lock.unlock();
    }
    return Flux.fromIterable(results);
  }

  @Override
  public Flux<AggregateResult> getAsyncResults() {
    return asyncResults.asFlux();
  }

  private void cleanup() {
    final List<String> toRemove = new ArrayList<>();
    final List<String> toDiscard = new ArrayList<>();

    lock.lock();
    try {
      final long now = System.currentTimeMillis();
      for (final Map.Entry<String, AggregateState> entry : store.entrySet()) {
        checkExpired(entry, now, toRemove, toDiscard);
      }
      toDiscard.forEach(store::remove);
      toRemove.forEach(this::emitAndRemove);
    } finally {
      lock.unlock();
    }
  }

  private void checkExpired(
      final Map.Entry<String, AggregateState> entry,
      final long now,
      final List<String> toRemove,
      final List<String> toDiscard) {
    final AggregateState state = entry.getValue();
    if (state.isExpired(now)) {
      if (state.isEmitOnTimeout()) {
        toRemove.add(entry.getKey());
      } else {
        toDiscard.add(entry.getKey());
      }
    }
  }

  private void emitAndRemove(final String key) {
    final AggregateState state = store.remove(key);
    if (state != null) {
      asyncResults.tryEmitNext(
          new AggregateResult(
              AggregateResult.Status.COMPLETED,
              state.getFinalResult(),
              key,
              state.getLastMessage()));
    }
  }

  /** Holds the state of an individual aggregate window. */
  @SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
  private static class AggregateState {
    /** The accumulated value from aggregation operations. */
    private Object accumulator;

    /** Count of values processed in this aggregate. */
    private int count;

    /** The last message that triggered an update. */
    private Message<?> lastMessage;

    /** Timestamp when this aggregate state was created. */
    private final long startTime;

    /** Timestamp of the last access or update. */
    private long lastAccessTime;

    /** Configuration settings for this aggregate. */
    private final AggregateConfig config;

    /* default */ AggregateState(final Message<?> message, final AggregateConfig config) {
      this.startTime = System.currentTimeMillis();
      this.lastAccessTime = startTime;
      this.lastMessage = message;
      this.config = config;
      this.accumulator = initAccumulator(config);
    }

    private Object initAccumulator(final AggregateConfig cfg) {
      return switch (cfg.aggregationType()) {
        case AGG_SUM, AGG_AVERAGE, AGG_MIN, AGG_MAX -> 0.0;
        case AGG_LIST -> new ArrayList<>();
        case AGG_FIRST, AGG_LAST -> null;
        case AGG_CUSTOM -> cfg.initVal();
        default -> null;
      };
    }

    /* default */ void update(final Object value, final Message<?> message) {
      this.lastAccessTime = System.currentTimeMillis();
      this.lastMessage = message;

      final Object normalizedValue = normalizeValue(value);
      if (normalizedValue != null) {
        count++;
        accumulator = performAggregation(accumulator, normalizedValue);
      }
    }

    private Object normalizeValue(final Object value) {
      if (value != null) {
        return value;
      }
      final String policy = config.nullPolicy();
      if (POLICY_ZERO.equals(policy)) {
        return 0.0;
      }
      if (POLICY_FAIL.equals(policy)) {
        throw new IllegalArgumentException("Null value not allowed");
      }
      return null;
    }

    private Object performAggregation(final Object acc, final Object val) {
      final Number numVal = isNumericAggregation() ? convertToNumber(val) : null;

      return switch (config.aggregationType()) {
        case AGG_SUM, AGG_AVERAGE -> ((Number) acc).doubleValue() + numVal.doubleValue();
        case AGG_LIST -> updateList(acc, val);
        case AGG_MIN -> aggregateMin(acc, numVal);
        case AGG_MAX -> aggregateMax(acc, numVal);
        case AGG_FIRST -> count == 1 ? val : acc;
        case AGG_LAST -> val;
        case AGG_CUSTOM ->
            SpelUtils.evaluateSync(config.accExp(), lastMessage, Map.of("acc", acc, "val", val));
        default -> acc;
      };
    }

    private boolean isNumericAggregation() {
      final String type = config.aggregationType();
      return AGG_SUM.equals(type)
          || AGG_AVERAGE.equals(type)
          || AGG_MIN.equals(type)
          || AGG_MAX.equals(type);
    }

    private Object updateList(final Object acc, final Object val) {
      @SuppressWarnings("unchecked")
      final List<Object> list = (List<Object>) acc;
      list.add(val);
      return list;
    }

    private Double aggregateMin(final Object acc, final Number val) {
      return count == 1
          ? val.doubleValue()
          : Math.min(((Number) acc).doubleValue(), val.doubleValue());
    }

    private Double aggregateMax(final Object acc, final Number val) {
      return count == 1
          ? val.doubleValue()
          : Math.max(((Number) acc).doubleValue(), val.doubleValue());
    }

    private Number convertToNumber(final Object val) {
      if (val instanceof Number number) {
        return number;
      }
      final String aggType = config.aggregationType();
      if (!AGG_LIST.equals(aggType) && !AGG_CUSTOM.equals(aggType)) {
        try {
          return Double.parseDouble(val.toString());
        } catch (final NumberFormatException e) {
          return 0.0;
        }
      }
      return 0.0;
    }

    /* default */ Object getFinalResult() {
      Object result = accumulator;
      final String aggType = config.aggregationType();
      if (AGG_AVERAGE.equals(aggType)) {
        result = count > 0 ? ((Number) accumulator).doubleValue() / count : 0.0;
      } else if (AGG_CUSTOM.equals(aggType) && config.resExp() != null) {
        result = SpelUtils.evaluateSync(config.resExp(), lastMessage, Map.of("acc", accumulator));
      }
      return result;
    }

    /* default */ boolean isTriggered() {
      return TYPE_COUNT.equals(config.windowType()) && count >= config.windowSize();
    }

    /* default */ boolean isExpired(final long now) {
      final String winType = config.windowType();
      return (TYPE_TIME.equals(winType) && now - startTime >= config.durationMs())
          || (TYPE_SESSION.equals(winType) && now - lastAccessTime >= config.durationMs());
    }

    /* default */ boolean isEmitOnTimeout() {
      return config.emitOnTimeout();
    }

    /* default */ Message<?> getLastMessage() {
      return lastMessage;
    }
  }
}
