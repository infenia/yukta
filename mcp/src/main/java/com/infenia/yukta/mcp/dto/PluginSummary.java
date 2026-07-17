// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.infenia.yukta.plugin.core.PluginCategory;

/**
 * Summary of an available plugin returned by the {@code list_plugins} MCP tool.
 *
 * @param type the plugin type identifier
 * @param category the plugin category (TRIGGER, PROCESSOR, or TERMINAL)
 */
public record PluginSummary(String type, PluginCategory category) {}
