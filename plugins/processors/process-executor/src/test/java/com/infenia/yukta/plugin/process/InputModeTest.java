// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Parsing of the inputMode setting. */
@NoArgsConstructor
class InputModeTest {

  @Test
  void aMissingValueMeansNone() {
    assertThat(InputMode.from(null)).isEqualTo(InputMode.NONE);
  }

  @Test
  void aNameIsReadIgnoringCaseAndSpaces() {
    assertThat(InputMode.from(" stdin ")).isEqualTo(InputMode.STDIN);
  }
}
