// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.store.MessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Channel implementation that persists messages to a MessageStore.
 *
 * <p>Wraps outbound messages through a wire-tap to the MessageStore for auditing, debugging, and
 * message history reconstruction. Inbound messages pass through unchanged.
 */
@Slf4j
@RequiredArgsConstructor
public class PersistingNodeMessageChannel implements NodeMessageChannel {

  /** The message store for persisting messages. */
  private final MessageStore messageStore;

  @Override
  public Flux<Message<?>> inbound(
      final String nodeId, final String executionId, final Flux<Message<?>> upstream) {
    return upstream;
  }

  @Override
  public Flux<Message<?>> outbound(
      final String nodeId, final String executionId, final Flux<Message<?>> pluginOutput) {
    log.atDebug()
        .setMessage("Applying message store wire-tap for node: {}")
        .addArgument(nodeId)
        .log();
    return pluginOutput.flatMap(msg -> messageStore.store(msg).thenReturn(msg));
  }
}
