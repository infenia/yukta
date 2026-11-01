// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.util.SinkEmit;
import io.micrometer.core.instrument.Metrics;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Hot feed of what happens to all executions, for supervisors and the monitoring API.
 *
 * <p>Best effort: an event is delivered to the subscribers that are ready for it, and a slow
 * subscriber misses events instead of slowing the workflows down.
 */
@Component
@NoArgsConstructor
public class ExecutionEventFeed {

  private static final int SUBSCRIBER_BUFFER = 10_000;
  private static final int SUBSCRIBER_PREFETCH = 256;

  /** Multicast sink; publishing never blocks and a slow subscriber drops events. */
  private final Sinks.Many<ExecutionEvent> sink = Sinks.many().multicast().directBestEffort();

  /**
   * Publishes an event to the current subscribers.
   *
   * @param event the event
   */
  public void publish(final ExecutionEvent event) {
    SinkEmit.offer(sink, event);
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
