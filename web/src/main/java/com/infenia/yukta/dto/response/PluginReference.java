// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reference to an available plugin in the session creation guide.
 *
 * @param type the plugin type
 * @param category the plugin category
 * @param description the plugin description
 */
@Schema(description = "Reference to an available plugin")
public record PluginReference(
    @Schema(description = "The plugin type") String type,
    @Schema(description = "The plugin category") String category,
    @Schema(description = "The plugin description") String description) {}
