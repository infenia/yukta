// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class FailureModeTest {

  @Test
  void parsesValues() {
    assertThat(FailureMode.from(null)).isEqualTo(FailureMode.ERROR);
    assertThat(FailureMode.from(" continue ")).isEqualTo(FailureMode.CONTINUE);
  }

  @Test
  void rejectsUnknown() {
    assertThatThrownBy(() -> FailureMode.from("x")).isInstanceOf(IllegalArgumentException.class);
  }
}
