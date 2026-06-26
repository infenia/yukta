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

class PluginReferenceTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String type = "gradle";
    String category = "PROCESSOR";
    String description = "Gradle quality checks plugin";

    // When
    PluginReference reference = new PluginReference(type, category, description);

    // Then
    assertThat(reference.type()).isEqualTo(type);
    assertThat(reference.category()).isEqualTo(category);
    assertThat(reference.description()).isEqualTo(description);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    PluginReference reference1 =
        new PluginReference("gradle", "PROCESSOR", "Gradle quality checks plugin");
    PluginReference reference2 =
        new PluginReference("gradle", "PROCESSOR", "Gradle quality checks plugin");

    // When-Then
    assertThat(reference1).isEqualTo(reference2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    PluginReference reference1 =
        new PluginReference("gradle", "PROCESSOR", "Gradle quality checks plugin");
    PluginReference reference2 =
        new PluginReference("maven", "PROCESSOR", "Gradle quality checks plugin");

    // When-Then
    assertThat(reference1).isNotEqualTo(reference2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    PluginReference reference =
        new PluginReference("gradle", "PROCESSOR", "Gradle quality checks plugin");

    // When
    String actual = reference.toString();

    // Then
    assertThat(actual).contains("PluginReference").contains("gradle");
  }
}
