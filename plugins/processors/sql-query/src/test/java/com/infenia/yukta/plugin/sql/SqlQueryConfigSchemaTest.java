// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schema the UI renders its form from. */
@NoArgsConstructor
class SqlQueryConfigSchemaTest {

  @Test
  void requiresUrlAndSqlAndKeepsThePasswordSecret() {
    final var schema = new SqlQueryPlugin().getConfigSchema();

    assertThat(schema.stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("url", "sql");
    assertThat(schema.stream().filter(f -> "password".equals(f.name())).findFirst().orElseThrow())
        .extracting(ConfigField::type)
        .isEqualTo(ConfigField.Type.SECRET);
  }
}
