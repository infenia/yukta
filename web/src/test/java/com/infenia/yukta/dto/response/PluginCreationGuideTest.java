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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for PluginCreationGuideTest. */
@NoArgsConstructor
class PluginCreationGuideTest {

  /** Architecture overview key. */
  private static final String OVERVIEW = "overview";

  /** Trigger plugin type key. */
  private static final String TRIGGER = "trigger";

  /** Template code constant. */
  private static final String CODE = "code";

  /** Integration examples constant. */
  private static final String EXAMPLES = "examples";

  /** Configuration reference constant. */
  private static final String CONFIG = "config";

  /** Validation checklist constant. */
  private static final String CHECKLIST = "checklist";

  /** Testing strategy constant. */
  private static final String STRATEGY = "strategy";

  /** Deployment guide constant. */
  private static final String GUIDE = "guide";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String architectureOverview = "Architecture overview";
    final Map<String, String> templateCode = Map.of(TRIGGER, "template code");
    final String integrationExamples = "Integration examples";
    final String configurationReference = "Configuration reference";
    final String validationChecklist = "Validation checklist";
    final String testingStrategy = "Testing strategy";
    final String deploymentGuide = "Deployment guide";

    // When
    final PluginCreationGuide guide =
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
    final PluginCreationGuide guide =
        new PluginCreationGuide(OVERVIEW, null, EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);

    // Then
    assertThat(guide.templateCode()).isEmpty();
    assertThat(guide.templateCode()).isUnmodifiable();
  }

  @Test
  void templateCode_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final PluginCreationGuide guide =
        new PluginCreationGuide(
            OVERVIEW, Map.of(TRIGGER, CODE), EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);

    // When-Then
    assertThatThrownBy(() -> guide.templateCode().put("processor", "more code"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final Map<String, String> templateCode = Map.of(TRIGGER, CODE);
    final PluginCreationGuide guide1 =
        new PluginCreationGuide(
            OVERVIEW, templateCode, EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);
    final PluginCreationGuide guide2 =
        new PluginCreationGuide(
            OVERVIEW, templateCode, EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);

    // When-Then
    assertThat(guide1).isEqualTo(guide2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final PluginCreationGuide guide1 =
        new PluginCreationGuide(
            "overview1", Map.of(), EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);
    final PluginCreationGuide guide2 =
        new PluginCreationGuide(
            "overview2", Map.of(), EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);

    // When-Then
    assertThat(guide1).isNotEqualTo(guide2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final PluginCreationGuide guide =
        new PluginCreationGuide(OVERVIEW, Map.of(), EXAMPLES, CONFIG, CHECKLIST, STRATEGY, GUIDE);

    // When
    final String actual = guide.toString();

    // Then
    assertThat(actual).contains("PluginCreationGuide");
  }
}
