// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.PluginTier;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Summary of a workflow plugin.
 *
 * @param type the plugin type
 * @param category the plugin category
 */
@Schema(description = "Summary of a workflow plugin")
public record PluginSummary(
    @Schema(description = "The plugin type") String type,
    @Schema(description = "The plugin category") PluginCategory category,
    @Schema(description = "How generic the plugin is") PluginTier tier,
    @Schema(description = "Whether the plugin reaches outside the engine; operators opt in")
        boolean restricted) {

  /** A core, unrestricted summary. */
  public PluginSummary(final String type, final PluginCategory category) {
    this(type, category, PluginTier.CORE, false);
  }
}
