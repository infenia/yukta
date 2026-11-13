// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.PluginTier;
import org.jspecify.annotations.Nullable;

/**
 * Summary of an available plugin returned by the {@code list_plugins} MCP tool.
 *
 * @param type the plugin type identifier
 * @param category the plugin category (TRIGGER, PROCESSOR, or TERMINAL)
 * @param tier how generic the plugin is (CORE, EXTENDED, SPECIFIC)
 * @param restricted true when it reaches outside the engine (processes, files, network, databases)
 * @param enabled false when the plugin exists but the operator has not enabled it
 * @param enableWith what an operator sets to enable it; absent when already enabled
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginSummary(
    String type,
    PluginCategory category,
    PluginTier tier,
    boolean restricted,
    boolean enabled,
    @Nullable String enableWith) {

  /** An enabled, core, unrestricted summary. */
  public PluginSummary(final String type, final PluginCategory category) {
    this(type, category, PluginTier.CORE, false, true, null);
  }
}
