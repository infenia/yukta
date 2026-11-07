// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The common settings are one list that the schema, the keys and the capabilities share. */
@NoArgsConstructor
class SupervisorCommonSettingsTest {

  @Test
  void schemaNamesTheFourCommonKeys() {
    assertThat(SupervisorCommonSettings.KEYS)
        .containsExactlyInAnyOrder("allowedCommands", "canAnswer", "maxRisk", "observeInternal");
  }

  @Test
  void allowedCommandsOffersEveryCommandName() {
    final ConfigField field =
        SupervisorCommonSettings.SCHEMA.stream()
            .filter(f -> "allowedCommands".equals(f.name()))
            .findFirst()
            .orElseThrow();

    assertThat(field.options()).isEqualTo(SupervisorCapabilities.commandNames());
    assertThat(field.options()).contains("PauseWorkflowCommand", "AnswerInputCommand");
  }

  @Test
  void factoryWithoutOverridesDescribesItselfByItsType() {
    final SupervisorFactory factory =
        new SupervisorFactory() {
          @Override
          public List<ConfigField> getConfigSchema() {
            return List.of();
          }

          @Override
          public String type() {
            return "plain";
          }

          @Override
          public ExecutionSupervisor create(final Supervisor profile) {
            throw new UnsupportedOperationException();
          }
        };

    assertThat(factory.defaultAllowedCommands(new Supervisor("s", "plain", Map.of(), false)))
        .isNull();
    assertThat(factory.describe().displayName()).isEqualTo("plain");
    assertThat(factory.describe().category()).isEqualTo(SupervisorCategory.INTEGRATION);
    assertThat(factory.getConfigSchema()).isEqualTo(List.of());
  }

  @Test
  void descriptorKeepsAnImmutableExample() {
    final SupervisorDescriptor descriptor =
        new SupervisorDescriptor(
            "n", "d", "u", SupervisorCategory.GUARD, java.util.Optional.empty(), null);

    assertThat(descriptor.exampleSettings()).isEqualTo(Map.of());
  }
}
