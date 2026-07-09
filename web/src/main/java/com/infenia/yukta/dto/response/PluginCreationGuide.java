// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Plugin creation guide record.
 *
 * @param architectureOverview overview of plugin architecture
 * @param templateCode template code for each plugin type
 * @param integrationExamples integration examples
 * @param configurationReference configuration reference
 * @param validationChecklist validation checklist
 * @param testingStrategy testing strategy
 * @param deploymentGuide deployment guide
 */
@Schema(description = "Comprehensive guide for creating Yukta plugins")
public record PluginCreationGuide(
    @Schema(description = "Overview of plugin architecture") String architectureOverview,
    @Schema(description = "Template code for each plugin type") Map<String, String> templateCode,
    @Schema(description = "Integration examples") String integrationExamples,
    @Schema(description = "Configuration reference") String configurationReference,
    @Schema(description = "Validation checklist") String validationChecklist,
    @Schema(description = "Testing strategy") String testingStrategy,
    @Schema(description = "Deployment guide") String deploymentGuide) {

  /** Compact constructor to ensure immutability of the template code map. */
  public PluginCreationGuide {
    templateCode = templateCode != null ? Map.copyOf(templateCode) : Map.of();
  }
}
