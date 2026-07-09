// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import java.util.Map;

/** Factory for obtaining the NodeMessageChannel to use for a given node. */
@FunctionalInterface
public interface NodeMessageChannelProvider {

  /**
   * Returns the channel to use for the given node. Called once per node assembly.
   *
   * @param nodeId the node identifier
   * @param config the node configuration
   * @return the channel to use for this node
   */
  NodeMessageChannel channelFor(String nodeId, Map<String, Object> config);
}
