// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Comprehensive guide for session creation containing naming conventions, configuration structure,
 * example configuration, available plugins, and common errors.
 *
 * @param namingConventions naming conventions for session identifiers and workflow names
 * @param configurationStructure description of the configuration structure
 * @param exampleSessionConfig example session configuration
 * @param workflowDefinitionFormat description of workflow definition format
 * @param availablePlugins list of available plugins for the session
 * @param commonErrors list of common errors and their resolutions
 */
@Schema(description = "Comprehensive guide for session creation")
public record SessionCreationGuide(
    @Schema(description = "Naming conventions for session identifiers and workflow names")
        String namingConventions,
    @Schema(description = "Description of the configuration structure")
        String configurationStructure,
    @Schema(description = "Example session configuration") String exampleSessionConfig,
    @Schema(description = "Description of workflow definition format")
        String workflowDefinitionFormat,
    @Schema(description = "List of available plugins for the session")
        List<PluginReference> availablePlugins,
    @Schema(description = "List of common errors and their resolutions")
        List<ErrorExample> commonErrors) {

  /** Compact constructor that wraps mutable lists with immutable views. */
  public SessionCreationGuide {
    availablePlugins = List.copyOf(availablePlugins);
    commonErrors = List.copyOf(commonErrors);
  }
}
