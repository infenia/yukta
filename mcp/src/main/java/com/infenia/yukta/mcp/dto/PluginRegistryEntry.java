// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Entry in the plugin registry, part of {@link ControlBusStatus}.
 *
 * @param type the plugin type identifier
 * @param category the plugin category (TRIGGER, PROCESSOR, or TERMINAL)
 * @param status the plugin status
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginRegistryEntry(String type, String category, String status) {}
