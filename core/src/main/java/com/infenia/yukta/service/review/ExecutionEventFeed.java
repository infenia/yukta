// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.util.SinkEmit;
import io.micrometer.core.instrument.Metrics;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Hot feed of what happens to all executions, for supervisors and the monitoring API.
 *
 * <p>Best effort: an event is delivered to the subscribers that are ready for it, and a slow
 * subscriber misses events instead of slowing the workflows down. The one exception is {@link
 * #reliable()}: a bounded queue for the events a person must not miss (input requests, workflow
 * status, control outcomes), read by the notification producer.
 */
@Slf4j
@Component
@NoArgsConstructor
public class ExecutionEventFeed {

  private static final int SUBSCRIBER_BUFFER = 10_000;
  private static final int SUBSCRIBER_PREFETCH = 256;

  /** Most events the reliable tap holds before it drops one. */
  private static final int TAP_CAPACITY = 10_000;

  /** The kinds of event the reliable tap carries. */
  private static final Set<Class<? extends ExecutionEvent>> TAPPED =
      Set.of(
          ExecutionEvent.InputRequested.class,
          ExecutionEvent.InputEscalated.class,
          ExecutionEvent.InputAnswered.class,
          ExecutionEvent.InputExpired.class,
          ExecutionEvent.InputCancelled.class,
          ExecutionEvent.WorkflowStatusChanged.class,
          ExecutionEvent.ControlOutcome.class);

  /** The remainder of the first drop of each {@code LOG_EVERY}. */
  private static final long FIRST = 1;

  /** Every this many drops, one error is logged, so a storm does not flood the log. */
  private static final long LOG_EVERY = 1_000;

  /** The reliable tap, or {@code null} while nobody reads it. */
  private final AtomicReference<Sinks.Many<ExecutionEvent>> tap = new AtomicReference<>();

  /** Events the tap dropped. */
  private final AtomicLong droppedCount = new AtomicLong();

  /** Multicast sink; publishing never blocks and a slow subscriber drops events. */
  private final Sinks.Many<ExecutionEvent> sink = Sinks.many().multicast().directBestEffort();

  /** Engine timings; best effort like the events, and read by the run analytics only. */
  private final Sinks.Many<EngineLatency> latencySink = Sinks.many().multicast().directBestEffort();

  /**
   * Publishes a timing of the engine to the current subscribers.
   *
   * @param latency the timing
   */
  public void publishLatency(final EngineLatency latency) {
    SinkEmit.offer(latencySink, latency);
  }

  /**
   * Engine timings published from now on, on a buffer and thread of their own.
   *
   * @return the timings
   */
  public Flux<EngineLatency> latencies() {
    return latencySink
        .asFlux()
        .onBackpressureBuffer(
            SUBSCRIBER_BUFFER,
            _ -> Metrics.counter("yukta.tracker.dropped", "kind", "latency").increment(),
            BufferOverflowStrategy.DROP_LATEST)
        .publishOn(Schedulers.boundedElastic(), SUBSCRIBER_PREFETCH);
  }

  /**
   * Publishes an event to the current subscribers.
   *
   * @param event the event
   */
  public void publish(final ExecutionEvent event) {
    SinkEmit.offer(sink, event);
    final Sinks.Many<ExecutionEvent> reliable = tap.get();
    if (reliable != null && isReliable(event) && !SinkEmit.offer(reliable, event)) {
      dropped();
    }
  }

  private static boolean isReliable(final ExecutionEvent event) {
    return TAPPED.contains(event.getClass());
  }

  private void dropped() {
    Metrics.counter("yukta.notifications.tap.dropped").increment();
    final long total = droppedCount.incrementAndGet();
    if (total % LOG_EVERY == FIRST) {
      log.atError()
          .addKeyValue("dropped", total)
          .log("The notification tap is full: an event that a person may need was dropped");
    }
  }

  /**
   * The events a person must not miss: input request changes, workflow status and control outcomes.
   * Unlike {@link #events()} they wait in a queue of 10 000 and are read in order by one reader;
   * only a full queue drops one, which is counted in {@code yukta.notifications.tap.dropped} and
   * logged as an error. Events published while nobody reads are not kept. A second call replaces
   * the reader of the first.
   *
   * @return the events from now on, for a single subscriber
   */
  public Flux<ExecutionEvent> reliable() {
    return Flux.defer(() -> {
      final Sinks.Many<ExecutionEvent> mine =
          Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(TAP_CAPACITY));
      tap.set(mine);
      return mine.asFlux().doFinally(_ -> tap.compareAndSet(mine, null));
    });
  }

  /**
   * Readers subscribed right now: a best-effort feed drops events published before one is.
   *
   * @return the number of subscribers
   */
  public int subscriberCount() {
    return sink.currentSubscriberCount();
  }

  /**
   * Events published from now on. Each subscriber gets its own buffer (overflow drops the latest
   * and is counted in {@code yukta.tracker.dropped}) and its own thread, so none can slow down the
   * publisher.
   *
   * @return the feed
   */
  public Flux<ExecutionEvent> events() {
    return sink.asFlux()
        .onBackpressureBuffer(
            SUBSCRIBER_BUFFER,
            _ -> Metrics.counter("yukta.tracker.dropped", "kind", "feed").increment(),
            BufferOverflowStrategy.DROP_LATEST)
        .publishOn(Schedulers.boundedElastic(), SUBSCRIBER_PREFETCH);
  }
}
