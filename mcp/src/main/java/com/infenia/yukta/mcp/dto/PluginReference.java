// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Reference to an available plugin, used in creation guides.
 *
 * @param type the plugin type identifier
 * @param category the plugin category (TRIGGER, PROCESSOR, or TERMINAL)
 * @param description human-readable plugin description
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginReference(String type, String category, String description) {}
