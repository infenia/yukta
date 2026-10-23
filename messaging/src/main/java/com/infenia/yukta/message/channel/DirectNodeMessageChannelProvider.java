// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import java.util.Map;

/** Default provider — returns one shared DirectNodeMessageChannel for every node. */
public class DirectNodeMessageChannelProvider implements NodeMessageChannelProvider {

  /** The channel handed to every node. */
  private final DirectNodeMessageChannel instance;

  /** Creates a provider whose channels have no size limit. */
  public DirectNodeMessageChannelProvider() {
    this(0);
  }

  /**
   * Creates a provider whose channels refuse large payloads.
   *
   * @param maxPayloadBytes largest payload in bytes; 0 or less means no limit
   */
  public DirectNodeMessageChannelProvider(final long maxPayloadBytes) {
    instance = new DirectNodeMessageChannel(maxPayloadBytes);
  }

  @Override
  public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
    return instance;
  }
}
