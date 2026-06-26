/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
