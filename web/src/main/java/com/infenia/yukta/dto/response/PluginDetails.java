// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Details of a workflow plugin.
 *
 * @param type the plugin type
 * @param category the plugin category
 * @param description the plugin description
 * @param usagePattern the plugin usage pattern
 * @param uiDesign the UI design metadata
 * @param outputPorts the list of available output ports
 */
@Schema(description = "Details of a workflow plugin")
public record PluginDetails(
    @Schema(description = "The plugin type") String type,
    @Schema(description = "The plugin category") PluginCategory category,
    @Schema(description = "The plugin description") String description,
    @Schema(description = "The plugin usage pattern") String usagePattern,
    @Schema(description = "The UI design metadata") UiDesign uiDesign,
    @Schema(description = "The list of available output ports") List<String> outputPorts) {

  /** Compact constructor to ensure immutability. */
  public PluginDetails {
    outputPorts = outputPorts != null ? List.copyOf(outputPorts) : List.of();
  }
}
