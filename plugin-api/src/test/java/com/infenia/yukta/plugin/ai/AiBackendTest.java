// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link AiBackend}. */
@NoArgsConstructor
class AiBackendTest {

  private static AiBackend backend(final List<ConfigField> options) {
    return new AiBackend() {
      @Override
      public String kind() {
        return "FAKE";
      }

      @Override
      public String description() {
        return "a fake";
      }

      @Override
      public List<ConfigField> options() {
        return List.copyOf(options);
      }
    };
  }

  @Test
  void info_carriesKindDescriptionAndOptions() {
    final ConfigField field = ConfigField.optional("model", ConfigField.Type.STRING, "m", "x");

    assertThat(backend(List.of(field)).info())
        .isEqualTo(new AiBackend.Info("FAKE", "a fake", List.of(field)));
  }

  @Test
  void info_copiesItsOptionsAndTreatsNullAsEmpty() {
    final List<ConfigField> options = new ArrayList<>();
    final AiBackend.Info info = new AiBackend.Info("K", "d", options);
    options.add(ConfigField.required("a", ConfigField.Type.STRING, "d"));

    assertThat(info.options()).isEmpty();
    assertThat(new AiBackend.Info("K", "d", null).options()).isEmpty();
  }
}
