// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import com.infenia.yukta.util.SinkEmit;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** The events of one execution: a replaying sink for followers and a list for plain reads. */
final class ResultChannel {

  /** Replays the newest events to a late subscriber. */
  private final Sinks.Many<ResultEvent> sink;

  /** The same events as a list, for reads that must not wait. */
  private final Queue<ResultEvent> kept = new ConcurrentLinkedQueue<>();

  /** Events kept for replay. */
  private final int retained;

  /** Keeps position numbers and the two copies in step when events arrive from several threads. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Set once the execution's end event is out. */
  private final AtomicBoolean ended = new AtomicBoolean();

  /** Next event position. */
  private long sequence;

  /* default */ ResultChannel(final int retained) {
    this.retained = retained;
    this.sink = Sinks.many().replay().limit(retained);
  }

  /* default */ Flux<ResultEvent> events() {
    return sink.asFlux();
  }

  /* default */ List<ResultEvent> since(final long fromSequence) {
    return kept.stream().filter(event -> event.sequence() >= fromSequence).toList();
  }

  /* default */ void emit(
      final ResultEvent.Kind kind, final String nodeId, final Object payload, final String status) {
    lock.lock();
    try {
      final long next = sequence;
      sequence = next + 1;
      final ResultEvent event =
          new ResultEvent(kind, nodeId, next, payload, status, next >= retained, Instant.now());
      kept.add(event);
      if (kept.size() > retained) {
        kept.poll();
      }
      SinkEmit.offer(sink, event);
    } finally {
      lock.unlock();
    }
  }

  /* default */ boolean finished() {
    return ended.get();
  }

  /* default */ void finish(final String status) {
    ended.set(true);
    emit(ResultEvent.Kind.EXECUTION_END, null, null, status);
    sink.tryEmitComplete();
  }
}
