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
