// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.Map;

/**
 * Guide for creating Yukta plugins, returned by the {@code get_plugin_creation_guide} MCP tool.
 *
 * @param architectureOverview overview of the plugin architecture
 * @param templateCode template code per plugin type
 * @param integrationExamples integration examples
 * @param configurationReference configuration reference
 * @param validationChecklist validation checklist
 * @param testingStrategy testing strategy
 * @param deploymentGuide deployment guide
 */
public record PluginCreationGuide(
    String architectureOverview,
    Map<String, String> templateCode,
    String integrationExamples,
    String configurationReference,
    String validationChecklist,
    String testingStrategy,
    String deploymentGuide) {

  /** Compact constructor to ensure immutability of the template code map. */
  public PluginCreationGuide {
    templateCode = templateCode == null ? Map.of() : Map.copyOf(templateCode);
  }
}
