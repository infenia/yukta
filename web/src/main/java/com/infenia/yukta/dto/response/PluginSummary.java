// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.core.PluginCategory;
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
    @Schema(description = "The plugin category") PluginCategory category) {}
