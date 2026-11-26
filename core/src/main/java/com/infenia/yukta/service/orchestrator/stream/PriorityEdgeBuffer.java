// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import java.util.Comparator;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * An edge buffer that hands the node its highest {@link Message#priority()} first, ties in arrival
 * order. It takes everything its input sends; when full it drops a message of the lowest priority
 * (the oldest or the newest of them).
 *
 * <p>ponytail: one lock around a sorted set, enough for one buffer per node; replace it if
 * profiling shows contention.
 */
final class PriorityEdgeBuffer {

  /** Highest priority first, then arrival order. */
  private static final Comparator<Waiting> ORDER =
      Comparator.comparingInt((Waiting w) -> -w.message().priority())
          .thenComparingLong(Waiting::seq);

  /** Guards {@link #waiting}, {@link #seq}, {@link #done} and {@link #failure}. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The waiting messages. */
  private final NavigableSet<Waiting> waiting = new TreeSet<>(ORDER);

  /** Serialises draining. */
  private final AtomicInteger wip = new AtomicInteger();

  /** Most messages waiting. */
  private final int size;

  /** Drop the oldest of the lowest priority when full, else the newest. */
  private final boolean dropOldest;

  /** Called with each dropped message. */
  private final Consumer<Message<?>> onDrop;

  /** Where the node takes its messages from. */
  private final FluxSink<Message<?>> sink;

  /** Arrival counter. */
  private long seq;

  /** The input ended (or failed) and nothing more arrives. */
  private boolean done;

  /** The input's failure, if it failed. */
  private Optional<Throwable> failure = Optional.empty();

  private PriorityEdgeBuffer(
      final int size,
      final boolean dropOldest,
      final Consumer<Message<?>> onDrop,
      final FluxSink<Message<?>> sink) {
    this.size = size;
    this.dropOldest = dropOldest;
    this.onDrop = onDrop;
    this.sink = sink;
  }

  /**
   * Puts the buffer in front of the input.
   *
   * @param input the node's input
   * @param size most messages waiting
   * @param dropOldest drop the oldest of the lowest priority when full, else the newest
   * @param onDrop called with each dropped message
   * @return the input, in priority order
   */
  /* default */ static Flux<Message<?>> buffered(
      final Flux<Message<?>> input,
      final int size,
      final boolean dropOldest,
      final Consumer<Message<?>> onDrop) {
    return Flux.create(
        sink -> new PriorityEdgeBuffer(size, dropOldest, onDrop, sink).attach(input));
  }

  private void attach(final Flux<Message<?>> input) {
    sink.onRequest(_ -> drain());
    sink.onDispose(
        input
            .contextWrite(sink.contextView())
            .subscribe(this::add, error -> end(Optional.of(error)), () -> end(Optional.empty())));
  }

  private void add(final Message<?> message) {
    Optional<Waiting> dropped = Optional.empty();
    lock.lock();
    try {
      waiting.add(new Waiting(message, seq));
      seq++;
      if (waiting.size() > size) {
        dropped = Optional.of(removeLowest());
      }
    } finally {
      lock.unlock();
    }
    dropped.ifPresent(w -> onDrop.accept(w.message()));
    drain();
  }

  /** Removes and returns the message to drop: the oldest or newest of the lowest priority. */
  private Waiting removeLowest() {
    final Waiting newest = waiting.last();
    final Waiting pick =
        dropOldest ? waiting.ceiling(new Waiting(newest.message(), Long.MIN_VALUE)) : newest;
    waiting.remove(pick);
    return pick;
  }

  private void end(final Optional<Throwable> error) {
    lock.lock();
    try {
      done = true;
      failure = error;
    } finally {
      lock.unlock();
    }
    drain();
  }

  /** Hands out what the node asked for; one thread at a time, others leave work for it. */
  private void drain() {
    if (wip.getAndIncrement() != 0) {
      return;
    }
    int missed = 1;
    do {
      emitRequested();
      finishIfDrained();
      missed = wip.addAndGet(-missed);
    } while (missed != 0);
  }

  private void emitRequested() {
    while (sink.requestedFromDownstream() > 0) {
      final Optional<Waiting> next = poll();
      if (next.isEmpty()) {
        return;
      }
      sink.next(next.get().message());
    }
  }

  private void finishIfDrained() {
    final boolean finished;
    lock.lock();
    try {
      finished = done && waiting.isEmpty();
    } finally {
      lock.unlock();
    }
    if (finished) {
      failure.ifPresentOrElse(sink::error, sink::complete);
    }
  }

  private Optional<Waiting> poll() {
    lock.lock();
    try {
      return Optional.ofNullable(waiting.pollFirst());
    } finally {
      lock.unlock();
    }
  }

  /** A waiting message and when it arrived. */
  private record Waiting(Message<?> message, long seq) {}
}
