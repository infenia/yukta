// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.store.MessageStore;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider that returns a persisting message channel when a MessageStore is available.
 *
 * <p>All nodes receive the same persisting channel instance which wires message storage into the
 * outbound message path.
 */
@Slf4j
@RequiredArgsConstructor
public class PersistingNodeMessageChannelProvider implements NodeMessageChannelProvider {

  /** The message store for persisting messages. */
  private final MessageStore messageStore;

  @Override
  public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
    log.atDebug().setMessage("Creating persisting channel for node: {}").addArgument(nodeId).log();
    return new PersistingNodeMessageChannel(messageStore);
  }
}
