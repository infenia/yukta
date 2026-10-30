// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.Message;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Passthrough channel. Inbound it drops a message whose {@link Message#expiration()} has passed.
 * With no size limit both sides otherwise return the Flux unchanged; with one, a message whose
 * payload is larger fails the stream with a {@link PayloadTooLargeException}, whether a plugin
 * produced it or is about to receive it.
 */
@Slf4j
public class DirectNodeMessageChannel implements NodeMessageChannel {

  /** Largest payload in bytes; 0 or less means no limit. */
  private final long maxPayloadBytes;

  /** Creates a channel with no size limit. */
  public DirectNodeMessageChannel() {
    this(0);
  }

  /**
   * Creates a channel that refuses large payloads.
   *
   * @param maxPayloadBytes largest payload in bytes; 0 or less means no limit
   */
  public DirectNodeMessageChannel(final long maxPayloadBytes) {
    this.maxPayloadBytes = maxPayloadBytes;
  }

  @Override
  public Flux<Message<?>> inbound(
      final String nodeId, final String executionId, final Flux<Message<?>> upstream) {
    return limited(nodeId, upstream.filter(DirectNodeMessageChannel::notExpired));
  }

  private static boolean notExpired(final Message<?> msg) {
    return msg.expiration() <= 0 || System.currentTimeMillis() < msg.expiration();
  }

  @Override
  public Flux<Message<?>> outbound(
      final String nodeId, final String executionId, final Flux<Message<?>> pluginOutput) {
    return limited(nodeId, pluginOutput);
  }

  private Flux<Message<?>> limited(final String nodeId, final Flux<Message<?>> flux) {
    return maxPayloadBytes <= 0
        ? flux
        : flux.doOnNext(
            msg -> {
              // the payload, but also the headers and failure text, which travel every edge too
              final long size =
                  PayloadSize.bytesIn(msg.payload())
                      + PayloadSize.bytesIn(msg.metadata())
                      + PayloadSize.bytesIn(msg.failureReason())
                      + PayloadSize.bytesIn(msg.exceptionDetail());
              if (size > maxPayloadBytes) {
                throw new PayloadTooLargeException(nodeId, size, maxPayloadBytes);
              }
            });
  }
}
