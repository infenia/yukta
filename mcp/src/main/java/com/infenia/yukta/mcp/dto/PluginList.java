// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * List of plugins returned by the {@code list_plugins} MCP tool. Wrapping the list in a record
 * keeps the tool's output schema generatable (bare {@code Mono<List<T>>} return types break schema
 * generation on the async stateless MCP stack).
 *
 * @param plugins the plugin summaries
 */
public record PluginList(List<PluginSummary> plugins) {

  /** Compact constructor to ensure immutability. */
  public PluginList {
    plugins = plugins == null ? List.of() : List.copyOf(plugins);
  }
}
