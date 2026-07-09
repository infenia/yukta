// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for PluginRegistryEntryTest. */
@NoArgsConstructor
class PluginRegistryEntryTest {

  /** Gradle plugin type constant. */
  private static final String GRADLE = "gradle";

  /** Processor category constant. */
  private static final String PROCESSOR = "PROCESSOR";

  /** Active status constant. */
  private static final String ACTIVE = "ACTIVE";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String type = GRADLE;
    final String category = PROCESSOR;
    final String status = ACTIVE;

    // When
    final PluginRegistryEntry entry = new PluginRegistryEntry(type, category, status);

    // Then
    assertThat(entry.type()).isEqualTo(type);
    assertThat(entry.category()).isEqualTo(category);
    assertThat(entry.status()).isEqualTo(status);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final PluginRegistryEntry entry1 = new PluginRegistryEntry(GRADLE, PROCESSOR, ACTIVE);
    final PluginRegistryEntry entry2 = new PluginRegistryEntry(GRADLE, PROCESSOR, ACTIVE);

    // When-Then
    assertThat(entry1).isEqualTo(entry2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final PluginRegistryEntry entry1 = new PluginRegistryEntry(GRADLE, PROCESSOR, ACTIVE);
    final PluginRegistryEntry entry2 = new PluginRegistryEntry("maven", PROCESSOR, ACTIVE);

    // When-Then
    assertThat(entry1).isNotEqualTo(entry2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final PluginRegistryEntry entry = new PluginRegistryEntry(GRADLE, PROCESSOR, ACTIVE);

    // When
    final String actual = entry.toString();

    // Then
    assertThat(actual).contains("PluginRegistryEntry").contains(GRADLE);
  }
}
