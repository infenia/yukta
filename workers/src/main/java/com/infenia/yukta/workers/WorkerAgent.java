// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import com.infenia.yukta.service.worker.WorkerRegistration;
import java.time.Duration;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.rsocket.RSocketRequester;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The worker's side of the connection: it connects out to the control plane, registers, renews its
 * lease and reconnects with a growing pause when the connection is lost or refused. Workers need no
 * open port, so they work behind NAT.
 */
@Slf4j
public class WorkerAgent {

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

  /** The running connection loop, null before {@link #start()}. */
  private Disposable connection;

  /**
   * Creates the agent.
   *
   * @param connect connects to the control plane (subscribed again for every attempt)
   * @param token the control plane's shared secret
   * @param registration what this worker announces
   * @param renewEvery how often the lease is renewed (a third of the control plane's lease is a
   *     good choice)
   * @param usedSlots how many node attempts this worker runs now
   */
  public WorkerAgent(
      final Mono<RSocketRequester> connect,
      final String token,
      final WorkerRegistration registration,
      final Duration renewEvery,
      final IntSupplier usedSlots) {
    this.connect = connect;
    this.token = token;
    this.registration = registration;
    this.renewEvery = renewEvery;
    this.usedSlots = usedSlots;
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

  /** Stops connecting and closes the connection. */
  public void stop() {
    if (connection != null) {
      connection.dispose();
    }
  }

  /** One connection: register, then renew until the control plane ends or refuses it. */
  /* default */ Mono<Void> session() {
    final Flux<WorkerFrame> out =
        Flux.concat(
            Mono.just(WorkerFrame.register(token, registration)),
            Flux.interval(renewEvery).map(_ -> WorkerFrame.lease(usedSlots.getAsInt())));
    return connect
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
