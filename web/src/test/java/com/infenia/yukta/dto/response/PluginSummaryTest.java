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

import com.infenia.yukta.plugin.core.PluginCategory;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for PluginSummaryTest. */
@NoArgsConstructor
class PluginSummaryTest {

  /** Gradle plugin type constant. */
  private static final String GRADLE = "gradle";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String type = GRADLE;
    final PluginCategory category = PluginCategory.PROCESSOR;

    // When
    final PluginSummary summary = new PluginSummary(type, category);

    // Then
    assertThat(summary.type()).isEqualTo(type);
    assertThat(summary.category()).isEqualTo(category);
  }

  @Test
  void category_enumValue_returnsCorrectCategory() {
    // Given
    final PluginSummary summary = new PluginSummary(GRADLE, PluginCategory.PROCESSOR);

    // When
    final PluginCategory actual = summary.category();

    // Then
    assertThat(actual).isEqualTo(PluginCategory.PROCESSOR);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final PluginSummary summary1 = new PluginSummary(GRADLE, PluginCategory.PROCESSOR);
    final PluginSummary summary2 = new PluginSummary(GRADLE, PluginCategory.PROCESSOR);

    // When-Then
    assertThat(summary1).isEqualTo(summary2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final PluginSummary summary1 = new PluginSummary(GRADLE, PluginCategory.PROCESSOR);
    final PluginSummary summary2 = new PluginSummary(GRADLE, PluginCategory.TRIGGER);

    // When-Then
    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final PluginSummary summary = new PluginSummary(GRADLE, PluginCategory.PROCESSOR);

    // When
    final String actual = summary.toString();

    // Then
    assertThat(actual).contains("PluginSummary").contains(GRADLE);
  }
}
