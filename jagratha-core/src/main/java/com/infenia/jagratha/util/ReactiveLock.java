package com.infenia.jagratha.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** A non-blocking reactive lock. */
public final class ReactiveLock {

  private final AtomicBoolean locked = new AtomicBoolean(false);
  private final Queue<Sinks.Empty<Void>> waiters = new ConcurrentLinkedQueue<>();

  /** Default constructor. */
  public ReactiveLock() {
    // Initializing the lock
  }

  /**
   * Acquire the lock.
   *
   * @return a Mono that completes when the lock is acquired
   */
  public Mono<Void> acquire() {
    return Mono.defer(
        () -> {
          if (locked.compareAndSet(false, true)) {
            return Mono.empty();
          }
          final Sinks.Empty<Void> sink = Sinks.empty();
          waiters.add(sink);
          return sink.asMono();
        });
  }

  /** Release the lock. */
  public void release() {
    final Sinks.Empty<Void> next = waiters.poll();
    if (next != null) {
      next.tryEmitEmpty();
    } else {
      locked.set(false);
    }
  }

  /**
   * Run an action within the lock.
   *
   * @param action the action to run
   * @param <T> the result type
   * @return a Mono containing the result of the action
   */
  public <T> Mono<T> withLock(final Mono<T> action) {
    return acquire().then(action).doFinally(signalType -> release());
  }
}
