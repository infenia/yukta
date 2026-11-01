// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InputKeysTest {

  @Test
  void theKeyIsStableAndHex() {
    final String key = InputKeys.of("s", "w", "n", "m", "approve");
    assertThat(key).isEqualTo(InputKeys.of("s", "w", "n", "m", "approve")).matches("[0-9a-f]{64}");
  }

  @Test
  void everyPartChangesTheKey() {
    final String key = InputKeys.of("s", "w", "n", "m", "a");
    assertThat(key)
        .isNotEqualTo(InputKeys.of("s2", "w", "n", "m", "a"))
        .isNotEqualTo(InputKeys.of("s", "w2", "n", "m", "a"))
        .isNotEqualTo(InputKeys.of("s", "w", "n2", "m", "a"))
        .isNotEqualTo(InputKeys.of("s", "w", "n", "m2", "a"))
        .isNotEqualTo(InputKeys.of("s", "w", "n", "m", "a2"));
  }

  @Test
  void movingCharacterBetweenPartsChangesTheKey() {
    assertThat(InputKeys.of("a", "bc", "n", "m", "x"))
        .isNotEqualTo(InputKeys.of("ab", "c", "n", "m", "x"));
  }

  @Test
  void blankPartIsRefused() {
    assertThatThrownBy(() -> InputKeys.of("s", "w", "n", " ", "a"))
        .hasMessage("messageId must not be blank");
    assertThatThrownBy(() -> InputKeys.of(null, "w", "n", "m", "a"))
        .hasMessage("sessionId must not be blank");
  }

  @Test
  void anUnknownAlgorithmIsReportedNotHidden() {
    assertThatThrownBy(() -> InputKeys.hash("NOPE", "x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("NOPE is not available");
  }
}
