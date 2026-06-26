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

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginDetailsTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String type = "gradle";
    PluginCategory category = PluginCategory.PROCESSOR;
    String description = "Gradle quality checks plugin";
    String usagePattern = "Build quality checks";
    UiDesign uiDesign = new UiDesign("<div>Gradle Plugin</div>", 100, 200);
    List<String> outputPorts = List.of("success", "failure");

    // When
    PluginDetails details =
        new PluginDetails(type, category, description, usagePattern, uiDesign, outputPorts);

    // Then
    assertThat(details.type()).isEqualTo(type);
    assertThat(details.category()).isEqualTo(category);
    assertThat(details.description()).isEqualTo(description);
    assertThat(details.usagePattern()).isEqualTo(usagePattern);
    assertThat(details.uiDesign()).isEqualTo(uiDesign);
    assertThat(details.outputPorts()).hasSize(2);
  }

  @Test
  void constructor_withNullOutputPorts_convertsToEmptyList() {
    // Given-When
    PluginDetails details =
        new PluginDetails(
            "gradle",
            PluginCategory.PROCESSOR,
            "Description",
            "Usage",
            new UiDesign("<div/>", 100, 200),
            null);

    // Then
    assertThat(details.outputPorts()).isEmpty();
    assertThat(details.outputPorts()).isUnmodifiable();
  }

  @Test
  void outputPorts_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    PluginDetails details =
        new PluginDetails(
            "gradle",
            PluginCategory.PROCESSOR,
            "Description",
            "Usage",
            new UiDesign("<div/>", 100, 200),
            List.of("success"));

    // When-Then
    assertThatThrownBy(() -> details.outputPorts().add("failure"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void uiDesign_record_returnsCorrectUiDesignData() {
    // Given
    UiDesign expectedUiDesign = new UiDesign("<div>Gradle</div>", 150, 250);
    PluginDetails details =
        new PluginDetails(
            "gradle",
            PluginCategory.PROCESSOR,
            "Description",
            "Usage",
            expectedUiDesign,
            List.of());

    // When
    UiDesign actual = details.uiDesign();

    // Then
    assertThat(actual.html()).contains("Gradle");
    assertThat(actual.width()).isEqualTo(150);
    assertThat(actual.height()).isEqualTo(250);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    UiDesign uiDesign = new UiDesign("<div/>", 100, 200);
    List<String> ports = List.of("success");
    PluginDetails details1 =
        new PluginDetails("gradle", PluginCategory.PROCESSOR, "Desc", "Usage", uiDesign, ports);
    PluginDetails details2 =
        new PluginDetails("gradle", PluginCategory.PROCESSOR, "Desc", "Usage", uiDesign, ports);

    // When-Then
    assertThat(details1).isEqualTo(details2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    UiDesign uiDesign = new UiDesign("<div/>", 100, 200);
    PluginDetails details1 =
        new PluginDetails("gradle", PluginCategory.PROCESSOR, "Desc", "Usage", uiDesign, List.of());
    PluginDetails details2 =
        new PluginDetails("maven", PluginCategory.PROCESSOR, "Desc", "Usage", uiDesign, List.of());

    // When-Then
    assertThat(details1).isNotEqualTo(details2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    PluginDetails details =
        new PluginDetails(
            "gradle",
            PluginCategory.PROCESSOR,
            "Description",
            "Usage",
            new UiDesign("<div/>", 100, 200),
            List.of());

    // When
    String actual = details.toString();

    // Then
    assertThat(actual).contains("PluginDetails").contains("gradle");
  }
}
