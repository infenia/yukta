// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
