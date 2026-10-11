// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CommentRequired")
class ConfigValidationTest {

  @Test
  void positiveLongUsesDefaultWhenAbsentAndAcceptsWholeNumbers() {
    assertThat(ConfigValidation.positiveLong(Map.of(), "n", 7)).isEqualTo(7);
    assertThat(ConfigValidation.positiveLong(Map.of("n", 3.0), "n", 7)).isEqualTo(3);
    assertThat(ConfigValidation.positiveInt(Map.of("n", 5), "n", 1)).isEqualTo(5);
  }

  @Test
  void positiveLongRejectsBadValues() {
    assertThatThrownBy(() -> ConfigValidation.positiveLong(Map.of("n", 0), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValidation.positiveLong(Map.of("n", 1.5), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValidation.positiveLong(Map.of("n", "x"), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ConfigValidation.positiveLong(Map.of("n", Double.POSITIVE_INFINITY), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValidation.positiveLong(Map.of("n", Double.NaN), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValidation.positiveLong(Map.of("n", 1e30), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValidation.positiveInt(Map.of("n", 1L << 40), "n", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiredStringRejectsMissingAndBlank() {
    assertThat(ConfigValidation.requiredString(Map.of("s", " a "), "s")).isEqualTo("a");
    assertThatThrownBy(() -> ConfigValidation.requiredString(Map.of(), "s"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValidation.requiredString(Map.of("s", " "), "s"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
