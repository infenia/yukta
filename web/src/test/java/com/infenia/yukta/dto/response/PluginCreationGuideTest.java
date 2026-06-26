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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginCreationGuideTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String architectureOverview = "Architecture overview";
    Map<String, String> templateCode = Map.of("trigger", "template code");
    String integrationExamples = "Integration examples";
    String configurationReference = "Configuration reference";
    String validationChecklist = "Validation checklist";
    String testingStrategy = "Testing strategy";
    String deploymentGuide = "Deployment guide";

    // When
    PluginCreationGuide guide =
        new PluginCreationGuide(
            architectureOverview,
            templateCode,
            integrationExamples,
            configurationReference,
            validationChecklist,
            testingStrategy,
            deploymentGuide);

    // Then
    assertThat(guide.architectureOverview()).isEqualTo(architectureOverview);
    assertThat(guide.templateCode()).hasSize(1);
    assertThat(guide.integrationExamples()).isEqualTo(integrationExamples);
    assertThat(guide.configurationReference()).isEqualTo(configurationReference);
    assertThat(guide.validationChecklist()).isEqualTo(validationChecklist);
    assertThat(guide.testingStrategy()).isEqualTo(testingStrategy);
    assertThat(guide.deploymentGuide()).isEqualTo(deploymentGuide);
  }

  @Test
  void constructor_withNullTemplateCode_convertsToEmptyMap() {
    // Given-When
    PluginCreationGuide guide =
        new PluginCreationGuide(
            "overview", null, "examples", "config", "checklist", "strategy", "guide");

    // Then
    assertThat(guide.templateCode()).isEmpty();
    assertThat(guide.templateCode()).isUnmodifiable();
  }

  @Test
  void templateCode_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    PluginCreationGuide guide =
        new PluginCreationGuide(
            "overview",
            Map.of("trigger", "code"),
            "examples",
            "config",
            "checklist",
            "strategy",
            "guide");

    // When-Then
    assertThatThrownBy(() -> guide.templateCode().put("processor", "more code"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    Map<String, String> templateCode = Map.of("trigger", "code");
    PluginCreationGuide guide1 =
        new PluginCreationGuide(
            "overview", templateCode, "examples", "config", "checklist", "strategy", "guide");
    PluginCreationGuide guide2 =
        new PluginCreationGuide(
            "overview", templateCode, "examples", "config", "checklist", "strategy", "guide");

    // When-Then
    assertThat(guide1).isEqualTo(guide2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    PluginCreationGuide guide1 =
        new PluginCreationGuide(
            "overview1", Map.of(), "examples", "config", "checklist", "strategy", "guide");
    PluginCreationGuide guide2 =
        new PluginCreationGuide(
            "overview2", Map.of(), "examples", "config", "checklist", "strategy", "guide");

    // When-Then
    assertThat(guide1).isNotEqualTo(guide2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    PluginCreationGuide guide =
        new PluginCreationGuide(
            "overview", Map.of(), "examples", "config", "checklist", "strategy", "guide");

    // When
    String actual = guide.toString();

    // Then
    assertThat(actual).contains("PluginCreationGuide");
  }
}
