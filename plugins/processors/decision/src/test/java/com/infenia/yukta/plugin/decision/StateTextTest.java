// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for StateText. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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

  @Test
  void truncate_tail_keepsTheLastCharacters() {
    final String log = "head-" + "x".repeat(200) + "-the cause";

    assertThat(StateText.truncate(log, 20, StateText.Keep.TAIL)).hasSize(20).endsWith("-the cause");
    assertThat(StateText.truncate("short", 20, StateText.Keep.TAIL)).isEqualTo("short");
  }

  @Test
  void truncate_tail_doesNotSplitSurrogatePair() {
    final String text = "x".repeat(10) + EMOJI + "yyyy";

    final String cut = StateText.truncate(text, 5, StateText.Keep.TAIL);

    assertThat(cut).isEqualTo("yyyy");
  }

  @Test
  void truncate_headTail_keepsBothEndsAndSaysHowMuchWasLeftOut() {
    final String log = "START" + "x".repeat(5000) + "END";

    final String cut = StateText.truncate(log, 200, StateText.Keep.HEAD_TAIL);

    assertThat(cut).hasSizeLessThanOrEqualTo(200).startsWith("START").endsWith("END");
    final java.util.regex.Matcher note =
        java.util.regex.Pattern.compile("\\n\\.\\.\\. \\[(\\d+) characters omitted\\] \\.\\.\\.\\n")
            .matcher(cut);
    assertThat(note.find()).isTrue();
    assertThat(Integer.parseInt(note.group(1)) + cut.length() - note.group().length())
        .isEqualTo(log.length());
  }

  @Test
  void truncate_headTail_doesNotSplitSurrogatePairs() {
    final String text = EMOJI.repeat(500);

    final String cut = StateText.truncate(text, 150, StateText.Keep.HEAD_TAIL);

    assertThat(cut).hasSizeLessThanOrEqualTo(150);
    assertThat(cut.codePoints().filter(c -> c == 0xFFFD).count()).isZero();
    assertThat(Character.isHighSurrogate(cut.charAt(cut.indexOf('\n') - 1))).isFalse();
    assertThat(Character.isLowSurrogate(cut.charAt(cut.lastIndexOf('\n') + 1))).isFalse();
  }

  @Test
  void truncate_headTail_withTinyLimit_justKeepsTheHead() {
    assertThat(StateText.truncate("abcdefghij", 5, StateText.Keep.HEAD_TAIL)).isEqualTo("abcde");
  }

  @Test
  void keep_parse_readsTheConfigSpelling() {
    assertThat(StateText.Keep.parse("head")).isEqualTo(StateText.Keep.HEAD);
    assertThat(StateText.Keep.parse(" Tail ")).isEqualTo(StateText.Keep.TAIL);
    assertThat(StateText.Keep.parse("headTail")).isEqualTo(StateText.Keep.HEAD_TAIL);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> StateText.Keep.parse("middle"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
