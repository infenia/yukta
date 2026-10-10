// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Runs work for the same key strictly one after another, in the order it was submitted.
 *
 * <p>Control commands for one execution must be applied in the order they were sent (a pause
 * followed by a resume must not be swapped). Work for different keys runs independently. A
 * submitted job waits for the previous one to finish, whether it succeeded, failed or was
 * cancelled, and the bookkeeping for a key disappears once its last job is done.
 */
@NoArgsConstructor
public final class CommandSequencer {

  /** The completion signal of the most recently submitted job, per key. */
  private final Map<String, Sinks.One<Void>> tails = new ConcurrentHashMap<>();

  /**
   * Orders {@code work} after everything previously sequenced under {@code key}.
   *
   * <p>Position in the queue is fixed when the returned Mono is subscribed.
   *
   * @param key what must be serialised, for example an execution id
   * @param work the job
   * @return a Mono that runs {@code work} after its predecessors and mirrors its result
   */
  public Mono<Void> sequence(final String key, final Mono<Void> work) {
    return Mono.defer(
        () -> {
          final Sinks.One<Void> mine = Sinks.one();
          final Sinks.One<Void> previous = tails.put(key, mine);
          final Mono<Void> turn = previous == null ? Mono.empty() : previous.asMono();
          return turn.then(work)
              .doFinally(
                  _ -> {
                    mine.tryEmitEmpty();
                    tails.remove(key, mine);
                  });
        });
  }

  /**
   * Number of keys with work queued or running.
   *
   * @return the count of keys currently tracked
   */
  public int pendingKeys() {
    return tails.size();
  }
}
