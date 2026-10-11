// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class FailureModeTest {

  @Test
  void from_defaultsParsesAndRejects() {
    assertThat(FailureMode.from(null)).isEqualTo(FailureMode.ERROR);
    assertThat(FailureMode.from(" continue ")).isEqualTo(FailureMode.CONTINUE);
    assertThatThrownBy(() -> FailureMode.from("nope")).isInstanceOf(IllegalArgumentException.class);
  }
}
