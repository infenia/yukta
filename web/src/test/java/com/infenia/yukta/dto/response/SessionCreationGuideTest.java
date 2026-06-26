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
import org.junit.jupiter.api.Test;

class SessionCreationGuideTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String namingConventions = "Use session-<id> format";
    String configurationStructure = "YAML structure";
    String exampleSessionConfig = "example: value";
    String workflowDefinitionFormat = "Workflow format description";
    List<PluginReference> availablePlugins =
        List.of(new PluginReference("plugin1", "TRIGGER", "Plugin description"));
    List<ErrorExample> commonErrors = List.of(new ErrorExample("error1", "cause", "resolution"));

    // When
    SessionCreationGuide guide =
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
    List<PluginReference> mutablePlugins =
        new ArrayList<>(List.of(new PluginReference("plugin1", "PROCESSOR", "Description")));
    List<ErrorExample> mutableErrors =
        new ArrayList<>(List.of(new ErrorExample("error1", "cause", "resolution")));

    // When
    SessionCreationGuide guide =
        new SessionCreationGuide(
            "naming", "config", "example", "workflow format", mutablePlugins, mutableErrors);

    // Then - verify lists are copied
    assertThat(guide.availablePlugins()).hasSize(1);
    assertThat(guide.commonErrors()).hasSize(1);
    // Modify original lists
    mutablePlugins.add(new PluginReference("plugin2", "TERMINAL", "Desc"));
    mutableErrors.add(new ErrorExample("error2", "cause2", "resolution2"));
    // Record should still have original size
    assertThat(guide.availablePlugins()).hasSize(1);
    assertThat(guide.commonErrors()).hasSize(1);
  }

  @Test
  void availablePlugins_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    SessionCreationGuide guide =
        new SessionCreationGuide(
            "naming",
            "config",
            "example",
            "format",
            List.of(new PluginReference("plugin1", "TRIGGER", "Desc")),
            List.of());

    // When-Then
    assertThatThrownBy(
            () -> guide.availablePlugins().add(new PluginReference("plugin2", "PROCESSOR", "Desc")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void commonErrors_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    SessionCreationGuide guide =
        new SessionCreationGuide(
            "naming",
            "config",
            "example",
            "format",
            List.of(),
            List.of(new ErrorExample("error1", "cause", "resolution")));

    // When-Then
    assertThatThrownBy(
            () -> guide.commonErrors().add(new ErrorExample("error2", "cause2", "resolution2")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    List<PluginReference> plugins = List.of(new PluginReference("p1", "TRIGGER", "Desc"));
    List<ErrorExample> errors = List.of(new ErrorExample("e1", "c1", "r1"));
    SessionCreationGuide guide1 =
        new SessionCreationGuide("naming", "config", "example", "format", plugins, errors);
    SessionCreationGuide guide2 =
        new SessionCreationGuide("naming", "config", "example", "format", plugins, errors);

    // When-Then
    assertThat(guide1).isEqualTo(guide2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    SessionCreationGuide guide1 =
        new SessionCreationGuide("naming1", "config", "example", "format", List.of(), List.of());
    SessionCreationGuide guide2 =
        new SessionCreationGuide("naming2", "config", "example", "format", List.of(), List.of());

    // When-Then
    assertThat(guide1).isNotEqualTo(guide2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    SessionCreationGuide guide =
        new SessionCreationGuide("naming", "config", "example", "format", List.of(), List.of());

    // When
    String actual = guide.toString();

    // Then
    assertThat(actual).contains("SessionCreationGuide").contains("naming");
  }
}
