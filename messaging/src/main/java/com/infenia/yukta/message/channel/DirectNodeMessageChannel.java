// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Flux;

/**
 * Passthrough channel. With no size limit both sides return the Flux unchanged; with one, a message
 * whose payload is larger fails the stream with a {@link PayloadTooLargeException}, whether a
 * plugin produced it or is about to receive it.
 */
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
    return limited(nodeId, upstream);
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
              final long size = PayloadSize.bytesIn(msg.payload());
              if (size > maxPayloadBytes) {
                throw new PayloadTooLargeException(nodeId, size, maxPayloadBytes);
              }
            });
  }
}
