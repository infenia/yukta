// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Record representing an entry in the plugin registry.
 *
 * @param type the plugin type
 * @param category the plugin category
 * @param status the plugin status
 */
@Schema(description = "Record representing an entry in the plugin registry")
public record PluginRegistryEntry(
    @Schema(description = "The plugin type", example = "gradle") String type,
    @Schema(
            description = "The plugin category",
            example = "PROCESSOR",
            allowableValues = {"TRIGGER", "PROCESSOR", "TERMINAL"})
        String category,
    @Schema(
            description = "The plugin status",
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "INACTIVE", "ERROR"})
        String status) {}
