// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Reference to an available plugin, used in creation guides.
 *
 * @param type the plugin type identifier
 * @param category the plugin category (TRIGGER, PROCESSOR, or TERMINAL)
 * @param description human-readable plugin description
 */
public record PluginReference(String type, String category, String description) {}
