// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import com.infenia.yukta.plugin.core.Plugin;
import java.util.Map;

/** Picks the object that serves one node, so a node's state does not leak into another's. */
final class NodePlugins {

  private NodePlugins() {}

  /**
   * Ask the shared plugin for its per-node object.
   *
   * @param shared the shared plugin instance
   * @param kind the interface the node runs through
   * @param config the node configuration
   * @param <T> the plugin interface
   * @return the plugin's per-node object, or {@code shared} if it gives none of that kind
   */
  /* default */ static <T extends Plugin> T forNode(
      final T shared, final Class<T> kind, final Map<String, Object> config) {
    final Plugin perNode = shared.forNode(config);
    return kind.isInstance(perNode) ? kind.cast(perNode) : shared;
  }
}
