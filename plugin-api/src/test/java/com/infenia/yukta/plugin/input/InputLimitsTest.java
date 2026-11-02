// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UseConcurrentHashMap")
class InputLimitsTest {

  @Test
  void idMustBePresentNotBlankAndShort() {
    assertThat(InputLimits.requireId("f", "ok")).isEqualTo("ok");
    assertThatThrownBy(() -> InputLimits.requireId("f", null)).hasMessage("f must not be blank");
    assertThatThrownBy(() -> InputLimits.requireId("f", "  ")).hasMessage("f must not be blank");
    assertThatThrownBy(() -> InputLimits.requireId("f", "x".repeat(129)))
        .hasMessage("f is longer than 128 characters");
    assertThat(InputLimits.requireId("f", "x".repeat(128))).hasSize(128);
  }

  @Test
  void textIsMeasuredInUtf8Bytes() {
    assertThat(InputLimits.requireText("t", "abc", 3)).isEqualTo("abc");
    assertThatThrownBy(() -> InputLimits.requireText("t", "abcd", 3))
        .hasMessage("t is longer than 3 bytes");
    // two characters, four bytes
    assertThatThrownBy(() -> InputLimits.requireText("t", "éé", 3))
        .hasMessage("t is longer than 3 bytes");
    assertThatThrownBy(() -> InputLimits.requireText("t", " ", 3))
        .hasMessage("t must not be blank");
    assertThatThrownBy(() -> InputLimits.requireText("t", null, 3))
        .hasMessage("t must not be blank");
  }

  @Test
  void optionalTextMayBeMissing() {
    assertThat(InputLimits.optionalText("t", null, 3)).isNull();
    assertThat(InputLimits.optionalText("t", "", 3)).isEmpty();
    assertThatThrownBy(() -> InputLimits.optionalText("t", "abcd", 3))
        .hasMessage("t is longer than 3 bytes");
  }

  @Test
  void mapIsMeasuredAsJson() {
    assertThatCode(() -> InputLimits.requireWithin("m", Map.of("a", "b"), 20))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> InputLimits.requireWithin("m", Map.of("a", "x".repeat(30)), 20))
        .hasMessage("m is longer than 20 bytes");
  }

  @Test
  void mapThatCannotBeWrittenIsRefused() {
    final Map<String, Object> selfReferencing = new HashMap<>();
    selfReferencing.put("me", selfReferencing);
    assertThatThrownBy(() -> InputLimits.requireWithin("m", selfReferencing, 1000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("m cannot be written as JSON");
  }

  @Test
  void confidenceIsProbability() {
    assertThatCode(
            () -> {
              InputLimits.requireConfidence(null);
              InputLimits.requireConfidence(0.0);
              InputLimits.requireConfidence(1.0);
            })
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> InputLimits.requireConfidence(1.1))
        .hasMessage("confidence must be between 0 and 1");
    assertThatThrownBy(() -> InputLimits.requireConfidence(-0.1))
        .hasMessage("confidence must be between 0 and 1");
    assertThatThrownBy(() -> InputLimits.requireConfidence(Double.NaN))
        .hasMessage("confidence must be between 0 and 1");
  }
}
