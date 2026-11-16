// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Runs the writes of the conversation service one after another, in the order they were made. The
 * store gives each write the next sequence number, so one writer is what makes the order of the
 * numbers the order of the live stream.
 *
 * <p>A write made from inside another write (a subscriber of the live stream that calls back) waits
 * its turn instead of recursing. A write that fails is logged and does not stop the ones behind it.
 */
@Slf4j
final class SerialWriter {

  /** Takes the jobs; thread-safe and safe against re-entry. */
  private final FluxSink<Mono<Void>> jobs;

  /** Runs the jobs one at a time. */
  private final Disposable subscription;

  /** The results of the writes that have not finished; failed when the writer is disposed. */
  private final Set<Sinks.One<?>> pending = ConcurrentHashMap.newKeySet();

  /* default */ SerialWriter() {
    final AtomicReference<FluxSink<Mono<Void>>> sink = new AtomicReference<>();
    this.subscription =
        Flux.<Mono<Void>>create(sink::set).concatMap(Function.identity()).subscribe();
    this.jobs = sink.get();
  }

  /**
   * Queues a write.
   *
   * @param operation the write; it starts when every earlier write is done
   * @param <T> what the write returns
   * @return what the write returns; empty if it returns nothing; its error if it fails. Cancelling
   *     the result does not cancel the write
   */
  /* default */ <T> Mono<T> submit(final Supplier<Mono<T>> operation) {
    return Mono.defer(() -> {
      if (subscription.isDisposed()) {
        return Mono.error(stopped());
      }
      final Sinks.One<T> result = Sinks.one();
      // registered before the job is queued: a dispose from now on fails this result
      pending.add(result);
      jobs.next(
          Mono.defer(operation)
              .doOnNext(result::tryEmitValue)
              .doOnError(error -> {
                log.atWarn().setCause(error).log("A conversation write failed");
                result.tryEmitError(error);
              })
              .doOnSuccess(value -> {
                if (value == null) {
                  result.tryEmitEmpty();
                }
              })
              .onErrorResume(error -> Mono.empty())
              .doFinally(signal -> pending.remove(result))
              .then());
      if (subscription.isDisposed()) {
        // disposed after the check above: the job may have been dropped, so fail it here
        result.tryEmitError(stopped());
      }
      return result.asMono();
    });
  }

  /**
   * Stops the writer. A write that has not finished fails with the same error a later submission
   * gets, so no caller waits for a signal that never comes.
   */
  /* default */ void dispose() {
    // taken before the cancel: cancelling runs doFinally, which removes each finished or dropped
    // write
    final List<Sinks.One<?>> unfinished = List.copyOf(pending);
    subscription.dispose();
    for (final Sinks.One<?> result : unfinished) {
      result.tryEmitError(stopped());
    }
    pending.clear();
  }

  private static IllegalStateException stopped() {
    return new IllegalStateException("The conversation writer is stopped");
  }
}
