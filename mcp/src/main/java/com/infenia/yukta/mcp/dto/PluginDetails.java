// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Details of a workflow plugin returned by the {@code get_plugin_details} MCP tool.
 *
 * @param type the plugin type identifier
 * @param category the plugin category
 * @param description the plugin description
 * @param usagePattern the plugin usage pattern
 * @param uiDesign the UI design metadata, or null if the plugin defines none
 * @param outputPorts the available output ports
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginDetails(
    String type,
    PluginCategory category,
    String description,
    String usagePattern,
    @Nullable UiDesign uiDesign,
    List<String> outputPorts) {

  /** Compact constructor to ensure immutability. */
  public PluginDetails {
    outputPorts = outputPorts == null ? List.of() : List.copyOf(outputPorts);
  }
}
