// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the {@link ConfigField} factories and its defensive copy. */
@SuppressWarnings("PMD.AtLeastOneConstructor")
class ConfigFieldTest {

  @Test
  void requiredHasNoDefault() {
    final ConfigField field = ConfigField.required("url", ConfigField.Type.STRING, "d");

    assertThat(field.required()).isTrue();
    assertThat(field.defaultValue()).isNull();
    assertThat(field.options()).isEmpty();
  }

  @Test
  void optionalCarriesItsDefault() {
    final ConfigField field = ConfigField.optional("timeout", ConfigField.Type.NUMBER, 30, "d");

    assertThat(field.required()).isFalse();
    assertThat(field.defaultValue()).isEqualTo(30);
  }

  @Test
  void choiceIsAnEnumWithOptions() {
    final ConfigField field = ConfigField.choice("method", false, "GET", "d", "GET", "POST");

    assertThat(field.type()).isEqualTo(ConfigField.Type.ENUM);
    assertThat(field.options()).containsExactly("GET", "POST");
  }

  @Test
  void optionsAreCopiedAndNullBecomesEmpty() {
    final List<String> options = new ArrayList<>(List.of("a"));
    final ConfigField copied =
        new ConfigField("x", ConfigField.Type.ENUM, false, null, options, "d");
    options.add("b");

    assertThat(copied.options()).containsExactly("a");
    assertThat(new ConfigField("x", ConfigField.Type.ENUM, false, null, null, "d").options())
        .isEmpty();
  }
}
