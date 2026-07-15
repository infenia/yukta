// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import java.util.Map;
import lombok.NoArgsConstructor;

/** Default provider — returns a singleton DirectNodeMessageChannel for every node. */
@NoArgsConstructor
public class DirectNodeMessageChannelProvider implements NodeMessageChannelProvider {

  /** Singleton instance of DirectNodeMessageChannel. */
  private static final DirectNodeMessageChannel INSTANCE = new DirectNodeMessageChannel();

  @Override
  public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
    return INSTANCE;
  }
}
