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
package com.infenia.yukta.model.api;

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
@SuppressWarnings("PMD.LongVariable")
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
