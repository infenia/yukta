// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for PluginDetailsTest. */
@NoArgsConstructor
class PluginDetailsTest {

  /** Gradle plugin type constant. */
  private static final String GRADLE = "gradle";

  /** Description constant. */
  private static final String DESCRIPTION = "Description";

  /** Usage pattern constant. */
  private static final String USAGE = "Usage";

  /** Success output port constant. */
  private static final String SUCCESS = "success";

  /** Default UI design HTML constant. */
  private static final String DEFAULT_UI_HTML = "<div/>";

  /** Description constant for testing. */
  private static final String DESC = "Desc";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String type = GRADLE;
    final PluginCategory category = PluginCategory.PROCESSOR;
    final String description = "Gradle quality checks plugin";
    final String usagePattern = "Build quality checks";
    final UiDesign uiDesign = new UiDesign("<div>Gradle Plugin</div>", 100, 200);
    final List<String> outputPorts = List.of(SUCCESS, "failure");

    // When
    final PluginDetails details =
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
    final PluginDetails details =
        new PluginDetails(
            GRADLE,
            PluginCategory.PROCESSOR,
            DESCRIPTION,
            USAGE,
            new UiDesign(DEFAULT_UI_HTML, 100, 200),
            null);

    // Then
    assertThat(details.outputPorts()).isEmpty();
    assertThat(details.outputPorts()).isUnmodifiable();
  }

  @Test
  void outputPorts_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final PluginDetails details =
        new PluginDetails(
            GRADLE,
            PluginCategory.PROCESSOR,
            DESCRIPTION,
            USAGE,
            new UiDesign(DEFAULT_UI_HTML, 100, 200),
            List.of(SUCCESS));

    // When-Then
    assertThatThrownBy(() -> details.outputPorts().add("failure"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void uiDesign_record_returnsCorrectUiDesignData() {
    // Given
    final UiDesign expectedUiDesign = new UiDesign("<div>gradle</div>", 150, 250);
    final PluginDetails details =
        new PluginDetails(
            GRADLE, PluginCategory.PROCESSOR, DESCRIPTION, USAGE, expectedUiDesign, List.of());

    // When
    final UiDesign actual = details.uiDesign();

    // Then
    assertThat(actual.html()).contains(GRADLE);
    assertThat(actual.width()).isEqualTo(150);
    assertThat(actual.height()).isEqualTo(250);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final UiDesign uiDesign = new UiDesign(DEFAULT_UI_HTML, 100, 200);
    final List<String> ports = List.of(SUCCESS);
    final PluginDetails details1 =
        new PluginDetails(GRADLE, PluginCategory.PROCESSOR, DESC, USAGE, uiDesign, ports);
    final PluginDetails details2 =
        new PluginDetails(GRADLE, PluginCategory.PROCESSOR, DESC, USAGE, uiDesign, ports);

    // When-Then
    assertThat(details1).isEqualTo(details2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final UiDesign uiDesign = new UiDesign(DEFAULT_UI_HTML, 100, 200);
    final PluginDetails details1 =
        new PluginDetails(GRADLE, PluginCategory.PROCESSOR, DESC, USAGE, uiDesign, List.of());
    final PluginDetails details2 =
        new PluginDetails("maven", PluginCategory.PROCESSOR, DESC, USAGE, uiDesign, List.of());

    // When-Then
    assertThat(details1).isNotEqualTo(details2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final PluginDetails details =
        new PluginDetails(
            GRADLE,
            PluginCategory.PROCESSOR,
            DESCRIPTION,
            USAGE,
            new UiDesign(DEFAULT_UI_HTML, 100, 200),
            List.of());

    // When
    final String actual = details.toString();

    // Then
    assertThat(actual).contains("PluginDetails").contains(GRADLE);
  }
}
