// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A workflow DAG node enriched with plugin metadata for UI rendering.
 *
 * @param nodeId the unique node identifier within the workflow
 * @param type the plugin type backing this node
 * @param category the plugin category, or null if the plugin type is unknown
 * @param description the plugin description, or null if the plugin type is unknown
 * @param uiDesign the UI design metadata, or null when the plugin provides none
 * @param outputPorts the config-aware output port names for this node
 */
@Schema(description = "A workflow DAG node enriched with plugin metadata for UI rendering")
public record WorkflowGraphNode(
    @Schema(description = "The unique node identifier within the workflow") String nodeId,
    @Schema(description = "The plugin type backing this node") String type,
    @Schema(
            description = "The plugin category, null if the plugin type is unknown",
            nullable = true)
        PluginCategory category,
    @Schema(
            description = "The plugin description, null if the plugin type is unknown",
            nullable = true)
        String description,
    @Schema(
            description = "The UI design metadata, null when the plugin provides none",
            nullable = true)
        UiDesign uiDesign,
    @Schema(description = "The config-aware output port names for this node")
        List<String> outputPorts) {

  /** Compact constructor to ensure immutability. */
  public WorkflowGraphNode {
    outputPorts = outputPorts != null ? List.copyOf(outputPorts) : List.of();
  }
}
