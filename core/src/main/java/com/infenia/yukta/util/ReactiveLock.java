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
package com.infenia.yukta.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** A non-blocking reactive lock. */
@NoArgsConstructor
public final class ReactiveLock {

  /** Flag indicating whether the lock is currently held. */
  private final AtomicBoolean locked = new AtomicBoolean(false);

  /** Queue of waiting sink signals for lock acquisition. */
  private final Queue<Sinks.Empty<Void>> waiters = new ConcurrentLinkedQueue<>();

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
