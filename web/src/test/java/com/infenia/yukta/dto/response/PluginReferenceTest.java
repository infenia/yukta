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

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for PluginReferenceTest. */
@NoArgsConstructor
class PluginReferenceTest {

  /** Gradle plugin type constant. */
  private static final String GRADLE = "gradle";

  /** Processor category constant. */
  private static final String PROCESSOR = "PROCESSOR";

  /** Gradle quality checks plugin description constant. */
  private static final String GRADLE_QUALITY_CHECKS_PLUGIN = "Gradle quality checks plugin";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String type = GRADLE;
    final String category = PROCESSOR;
    final String description = GRADLE_QUALITY_CHECKS_PLUGIN;

    // When
    final PluginReference reference = new PluginReference(type, category, description);

    // Then
    assertThat(reference.type()).isEqualTo(type);
    assertThat(reference.category()).isEqualTo(category);
    assertThat(reference.description()).isEqualTo(description);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final PluginReference reference1 =
        new PluginReference(GRADLE, PROCESSOR, GRADLE_QUALITY_CHECKS_PLUGIN);
    final PluginReference reference2 =
        new PluginReference(GRADLE, PROCESSOR, GRADLE_QUALITY_CHECKS_PLUGIN);

    // When-Then
    assertThat(reference1).isEqualTo(reference2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final PluginReference reference1 =
        new PluginReference(GRADLE, PROCESSOR, GRADLE_QUALITY_CHECKS_PLUGIN);
    final PluginReference reference2 =
        new PluginReference("maven", PROCESSOR, GRADLE_QUALITY_CHECKS_PLUGIN);

    // When-Then
    assertThat(reference1).isNotEqualTo(reference2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final PluginReference reference =
        new PluginReference(GRADLE, PROCESSOR, GRADLE_QUALITY_CHECKS_PLUGIN);

    // When
    final String actual = reference.toString();

    // Then
    assertThat(actual).contains("PluginReference").contains(GRADLE);
  }
}
