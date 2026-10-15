// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import com.infenia.yukta.service.worker.WorkerRegistration;
import io.rsocket.RSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.rsocket.RSocketRequester;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

/**
 * The worker's side of the connection: it connects out to the control plane, registers, renews its
 * lease and reconnects with a growing pause when the connection is lost or refused. Workers need no
 * open port, so they work behind NAT.
 */
@Slf4j
public class WorkerAgent {

  /** How often a draining worker looks at its running attempts. */
  private static final long DRAIN_POLL_MILLIS = 50;

  /** Longest pause between two connection attempts. */
  private static final Duration MAX_PAUSE = Duration.ofSeconds(30);

  /** Connects to the control plane; subscribed again for every connection attempt. */
  private final Mono<RSocketRequester> connect;

  /** The control plane's shared secret. */
  private final String token;

  /** What this worker announces. */
  private final WorkerRegistration registration;

  /** How often the lease is renewed. */
  private final Duration renewEvery;

  /** How many node attempts this worker runs now. */
  private final IntSupplier usedSlots;

  /** Longest {@link #stop()} waits for running attempts to finish. */
  private final Duration drainTimeout;

  /** True once the worker is stopping and takes no new attempts. */
  private final AtomicBoolean draining = new AtomicBoolean();

  /** Frames to send on the open stream besides the regular lease renewals. */
  private final Sinks.Many<WorkerFrame> urgent = Sinks.many().multicast().directBestEffort();

  /** The running connection loop, null before {@link #start()}. */
  private Disposable connection;

  /** The connection in use, so {@link #stop()} can close it; null when there is none. */
  private final AtomicReference<RSocketRequester> current = new AtomicReference<>();

  /**
   * Creates the agent.
   *
   * @param connect connects to the control plane (subscribed again for every attempt)
   * @param token the control plane's shared secret
   * @param registration what this worker announces
   * @param renewEvery how often the lease is renewed (a third of the control plane's lease is a
   *     good choice)
   * @param usedSlots how many node attempts this worker runs now
   * @param drainTimeout longest {@link #stop()} waits for running attempts to finish
   */
  public WorkerAgent(
      final Mono<RSocketRequester> connect,
      final String token,
      final WorkerRegistration registration,
      final Duration renewEvery,
      final IntSupplier usedSlots,
      final Duration drainTimeout) {
    this.connect = connect;
    this.token = token;
    this.registration = registration;
    this.renewEvery = renewEvery;
    this.usedSlots = usedSlots;
    this.drainTimeout = drainTimeout;
  }

  /** Starts connecting; the agent keeps reconnecting until {@link #stop()}. */
  public void start() {
    connection =
        session()
            .retryWhen(
                Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .maxBackoff(MAX_PAUSE)
                    .doBeforeRetry(
                        signal ->
                            log.atWarn()
                                .setCause(signal.failure())
                                .log("Lost the control plane, reconnecting")))
            .subscribe();
  }

  /**
   * Drains, then stops connecting and closes the connection. The control plane is told at once that
   * the worker takes no new attempts; the attempts that run are given up to the drain timeout to
   * finish. What is still running after that is failed when the connection closes, and the engine
   * retries it on another worker.
   */
  public void stop() {
    drain();
    if (connection != null) {
      connection.dispose();
    }
    final RSocketRequester open = current.get();
    final RSocket socket = open == null ? null : open.rsocket();
    if (socket != null) {
      socket.dispose();
    }
  }

  /** Tells the control plane to send nothing more and waits for the running attempts. */
  private void drain() {
    if (draining.compareAndSet(false, true) && current.get() != null) {
      urgent.tryEmitNext(WorkerFrame.lease(usedSlots.getAsInt(), true));
      final long deadline = System.nanoTime() + drainTimeout.toNanos();
      while (usedSlots.getAsInt() > 0 && System.nanoTime() - deadline < 0) {
        try {
          Thread.sleep(DRAIN_POLL_MILLIS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /* default */ void connected(final RSocketRequester requester) {
    current.set(requester);
  }

  /** One connection: register, then renew until the control plane ends or refuses it. */
  /* default */ Mono<Void> session() {
    final Flux<WorkerFrame> out =
        Flux.concat(
            Mono.just(WorkerFrame.register(token, registration)),
            Flux.merge(
                Flux.interval(renewEvery)
                    .map(_ -> WorkerFrame.lease(usedSlots.getAsInt(), draining.get())),
                urgent.asFlux()));
    return connect
        .doOnNext(this::connected)
        .flatMapMany(
            requester ->
                requester.route("worker.session").data(out).retrieveFlux(WorkerFrame.class))
        .doOnNext(this::check)
        .then(Mono.error(new IllegalStateException("The control plane closed the connection")));
  }

  private void check(final WorkerFrame answer) {
    if (answer.type() == WorkerFrame.Type.REJECT) {
      throw new IllegalStateException("Refused by the control plane: " + answer.message());
    }
  }
}
