// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The configuration schemas the UI renders its forms from. */
@NoArgsConstructor
class FileIoConfigSchemaTest {

  private static List<String> names(final List<ConfigField> schema) {
    return schema.stream().map(ConfigField::name).toList();
  }

  @Test
  void readNeedsBaseDirectory() {
    final var schema = new FileReadPlugin().getConfigSchema();
    assertThat(names(schema)).containsExactly("baseDir", "path");
    assertThat(schema.getFirst().required()).isTrue();
  }

  @Test
  void writeAddsContentFieldAndAppend() {
    final var schema = new FileWritePlugin().getConfigSchema();
    assertThat(names(schema)).containsExactly("baseDir", "path", "contentField", "append");
    assertThat(schema.getFirst().required()).isTrue();
  }
}
