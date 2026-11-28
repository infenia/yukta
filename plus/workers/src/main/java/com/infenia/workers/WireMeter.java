// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.plugins.RSocketInterceptor;
import io.rsocket.util.RSocketProxy;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Counts the bytes of the request channels between the control plane and its workers, {@code
 * yukta.worker.bytes{direction=sent|received}}: the payloads after the codec and before
 * fragmentation, TLS and framing. Both kinds of stream are channels: the worker's session (served
 * here, so a responder) and each node attempt (requested here, so a requester).
 */
final class WireMeter {

  /** Bytes this server sent. */
  private final Counter sent;

  /** Bytes this server received. */
  private final Counter received;

  /**
   * Registers the counters.
   *
   * @param meters where they are registered
   */
  /* default */ WireMeter(final MeterRegistry meters) {
    sent = counter(meters, "sent");
    received = counter(meters, "received");
  }

  private static Counter counter(final MeterRegistry meters, final String direction) {
    return Counter.builder("yukta.worker.bytes")
        .description("Payload bytes on the worker connections (after the codec, before TLS)")
        .baseUnit("bytes")
        .tag("direction", direction)
        .register(meters);
  }

  /**
   * Wraps the requester side: what goes into a channel is sent, what comes back is received.
   *
   * @return the interceptor
   */
  /* default */ RSocketInterceptor requester() {
    return socket -> channel(socket, sent, received);
  }

  /**
   * Wraps the responder side: what comes in is received, what the handler answers is sent.
   *
   * @return the interceptor
   */
  /* default */ RSocketInterceptor responder() {
    return socket -> channel(socket, received, sent);
  }

  /**
   * Counts what goes into the socket's channels with {@code into}, what comes out with {@code
   * outOf}.
   */
  private static RSocket channel(final RSocket socket, final Counter into, final Counter outOf) {
    return new RSocketProxy(socket) {
      @Override
      public Flux<Payload> requestChannel(final Publisher<Payload> payloads) {
        return super.requestChannel(Flux.from(payloads).doOnNext(payload -> count(into, payload)))
            .doOnNext(payload -> count(outOf, payload));
      }
    };
  }

  private static void count(final Counter counter, final Payload payload) {
    counter.increment(
        payload.data().readableBytes()
            + (payload.hasMetadata() ? payload.metadata().readableBytes() : 0));
  }
}
