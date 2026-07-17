// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * Guide for creating a Yukta session, returned by the {@code get_session_creation_instructions} MCP
 * tool.
 *
 * @param namingConventions naming conventions for session identifiers and workflow names
 * @param configurationStructure description of the configuration structure
 * @param exampleSessionConfig example session configuration JSON
 * @param workflowDefinitionFormat description of the workflow definition format
 * @param availablePlugins the plugins currently registered and usable in workflows
 * @param commonErrors common configuration errors and their resolutions
 */
public record SessionCreationGuide(
    String namingConventions,
    String configurationStructure,
    String exampleSessionConfig,
    String workflowDefinitionFormat,
    List<PluginReference> availablePlugins,
    List<ErrorExample> commonErrors) {

  /** Compact constructor to ensure immutability. */
  public SessionCreationGuide {
    availablePlugins = availablePlugins == null ? List.of() : List.copyOf(availablePlugins);
    commonErrors = commonErrors == null ? List.of() : List.copyOf(commonErrors);
  }
}
