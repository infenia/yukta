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

import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionCreationGuideTest. */
@NoArgsConstructor
class SessionCreationGuideTest {

  /** Naming conventions constant. */
  private static final String NAMING = "naming";

  /** Configuration structure constant. */
  private static final String CONFIG = "config";

  /** Example session config constant. */
  private static final String EXAMPLE = "example";

  /** Workflow definition format constant. */
  private static final String FORMAT = "format";

  /** Plugin reference constant. */
  private static final String PLUGIN1 = "plugin1";

  /** Trigger plugin type constant. */
  private static final String TRIGGER = "TRIGGER";

  /** Description constant. */
  private static final String DESC = "Desc";

  /** Error example constant. */
  private static final String ERROR1 = "error1";

  /** Error cause constant. */
  private static final String CAUSE = "cause";

  /** Error resolution constant. */
  private static final String RESOLUTION = "resolution";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String namingConventions = "Use session-<id> format";
    final String configurationStructure = "YAML structure";
    final String exampleSessionConfig = "example: value";
    final String workflowDefinitionFormat = "Workflow format description";
    final List<PluginReference> availablePlugins =
        List.of(new PluginReference(PLUGIN1, TRIGGER, "Plugin description"));
    final List<ErrorExample> commonErrors = List.of(new ErrorExample(ERROR1, CAUSE, RESOLUTION));

    // When
    final SessionCreationGuide guide =
        new SessionCreationGuide(
            namingConventions,
            configurationStructure,
            exampleSessionConfig,
            workflowDefinitionFormat,
            availablePlugins,
            commonErrors);

    // Then
    assertThat(guide.namingConventions()).isEqualTo(namingConventions);
    assertThat(guide.configurationStructure()).isEqualTo(configurationStructure);
    assertThat(guide.exampleSessionConfig()).isEqualTo(exampleSessionConfig);
    assertThat(guide.workflowDefinitionFormat()).isEqualTo(workflowDefinitionFormat);
    assertThat(guide.availablePlugins()).hasSize(1);
    assertThat(guide.commonErrors()).hasSize(1);
  }

  @Test
  void constructor_withMutableLists_copiesListsImmutably() {
    // Given
    final List<PluginReference> mutablePlugins =
        new ArrayList<>(List.of(new PluginReference(PLUGIN1, "PROCESSOR", "Description")));
    final List<ErrorExample> mutableErrors =
        new ArrayList<>(List.of(new ErrorExample(ERROR1, CAUSE, RESOLUTION)));

    // When
    final SessionCreationGuide guide =
        new SessionCreationGuide(
            NAMING, CONFIG, EXAMPLE, "workflow format", mutablePlugins, mutableErrors);

    // Then - verify lists are copied
    assertThat(guide.availablePlugins()).hasSize(1);
    assertThat(guide.commonErrors()).hasSize(1);
    // Modify original lists
    mutablePlugins.add(new PluginReference("plugin2", "TERMINAL", DESC));
    mutableErrors.add(new ErrorExample("error2", "cause2", "resolution2"));
    // Record should still have original size
    assertThat(guide.availablePlugins()).hasSize(1);
    assertThat(guide.commonErrors()).hasSize(1);
  }

  @Test
  void availablePlugins_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionCreationGuide guide =
        new SessionCreationGuide(
            NAMING,
            CONFIG,
            EXAMPLE,
            FORMAT,
            List.of(new PluginReference(PLUGIN1, TRIGGER, DESC)),
            List.of());

    // When-Then
    assertThatThrownBy(
            () -> guide.availablePlugins().add(new PluginReference("plugin2", "PROCESSOR", DESC)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void commonErrors_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionCreationGuide guide =
        new SessionCreationGuide(
            NAMING,
            CONFIG,
            EXAMPLE,
            FORMAT,
            List.of(),
            List.of(new ErrorExample(ERROR1, CAUSE, RESOLUTION)));

    // When-Then
    assertThatThrownBy(
            () -> guide.commonErrors().add(new ErrorExample("error2", "cause2", "resolution2")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final List<PluginReference> plugins = List.of(new PluginReference("p1", TRIGGER, DESC));
    final List<ErrorExample> errors = List.of(new ErrorExample("e1", "c1", "r1"));
    final SessionCreationGuide guide1 =
        new SessionCreationGuide(NAMING, CONFIG, EXAMPLE, FORMAT, plugins, errors);
    final SessionCreationGuide guide2 =
        new SessionCreationGuide(NAMING, CONFIG, EXAMPLE, FORMAT, plugins, errors);

    // When-Then
    assertThat(guide1).isEqualTo(guide2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SessionCreationGuide guide1 =
        new SessionCreationGuide("naming1", CONFIG, EXAMPLE, FORMAT, List.of(), List.of());
    final SessionCreationGuide guide2 =
        new SessionCreationGuide("naming2", CONFIG, EXAMPLE, FORMAT, List.of(), List.of());

    // When-Then
    assertThat(guide1).isNotEqualTo(guide2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionCreationGuide guide =
        new SessionCreationGuide(NAMING, CONFIG, EXAMPLE, FORMAT, List.of(), List.of());

    // When
    final String actual = guide.toString();

    // Then
    assertThat(actual).contains("SessionCreationGuide").contains(NAMING);
  }
}
