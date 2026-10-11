// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for StateText. */
@NoArgsConstructor
class StateTextTest {

  /** A character stored as two chars. */
  private static final String EMOJI = new String(Character.toChars(0x1F600));

  @Test
  void truncate_shortTextIsKept() {
    assertThat(StateText.truncate("short")).isEqualTo("short");
    assertThat(StateText.truncate("x".repeat(StateText.MAX))).hasSize(StateText.MAX);
  }

  @Test
  void truncate_longTextIsCutToTheLimit() {
    assertThat(StateText.truncate("x".repeat(StateText.MAX + 5))).hasSize(StateText.MAX);
  }

  @Test
  void truncate_doesNotSplitSurrogatePair() {
    final String text = "x".repeat(StateText.MAX - 1) + EMOJI + "tail";

    final String cut = StateText.truncate(text);

    assertThat(cut).hasSize(StateText.MAX - 1).doesNotContain(EMOJI);
    assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
  }

  @Test
  void truncate_keepsCompleteSurrogatePairBeforeTheLimit() {
    final String text = "x".repeat(StateText.MAX - 2) + EMOJI + "tail";

    assertThat(StateText.truncate(text)).hasSize(StateText.MAX).endsWith(EMOJI);
  }
}
