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

import org.junit.jupiter.api.Test;

class PluginRegistryEntryTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String type = "gradle";
    String category = "PROCESSOR";
    String status = "ACTIVE";

    // When
    PluginRegistryEntry entry = new PluginRegistryEntry(type, category, status);

    // Then
    assertThat(entry.type()).isEqualTo(type);
    assertThat(entry.category()).isEqualTo(category);
    assertThat(entry.status()).isEqualTo(status);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    PluginRegistryEntry entry1 = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");
    PluginRegistryEntry entry2 = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");

    // When-Then
    assertThat(entry1).isEqualTo(entry2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    PluginRegistryEntry entry1 = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");
    PluginRegistryEntry entry2 = new PluginRegistryEntry("maven", "PROCESSOR", "ACTIVE");

    // When-Then
    assertThat(entry1).isNotEqualTo(entry2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    PluginRegistryEntry entry = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");

    // When
    String actual = entry.toString();

    // Then
    assertThat(actual).contains("PluginRegistryEntry").contains("gradle");
  }
}
