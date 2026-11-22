// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for GrepPattern. */
@NoArgsConstructor
class GrepPatternTest {

  @Test
  void compile_acceptsValidPattern() {
    final Pattern pattern = GrepPattern.compile("FAIL(ED)?");

    assertThat(GrepPattern.matches(pattern, "Test > x FAILED")).isTrue();
    assertThat(GrepPattern.matches(pattern, "all good")).isFalse();
  }

  @Test
  void compile_refusesLongPattern() {
    final String tooLong = "a".repeat(GrepPattern.MAX_PATTERN + 1);

    assertThatThrownBy(() -> GrepPattern.compile(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longer than");
  }

  @Test
  void compile_refusesAnInvalidPattern() {
    assertThatThrownBy(() -> GrepPattern.compile("("))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("regular expression");
  }

  @Test
  void matches_refusesRunawayPattern() {
    final Pattern trap = GrepPattern.compile("(a*)*\\1b");
    final String line = "a".repeat(40) + "!";

    assertThatThrownBy(() -> GrepPattern.matches(trap, line))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too expensive");
  }

  @Test
  void matches_looksOnlyAtKeptPartOfLine() {
    final String line = "x".repeat(GrepPattern.MAX_LINE) + "needle";

    assertThat(GrepPattern.matches(GrepPattern.compile("needle"), line)).isFalse();
  }

  @Test
  void cut_keepsShortLinesAndCutsLongOnes() {
    assertThat(GrepPattern.cut("short")).isEqualTo("short");
    assertThat(GrepPattern.cut("y".repeat(5000))).hasSize(GrepPattern.MAX_LINE);
  }

  @Test
  void budgetedLine_readsLikeItsText() {
    final CharSequence line = new GrepPattern.Budgeted("hello", System.nanoTime() + 1_000_000_000L);

    assertThat(line).hasSize(5);
    assertThat(line.charAt(1)).isEqualTo('e');
    assertThat(line.subSequence(1, 3)).hasToString("el");
    assertThat(line).hasToString("hello");
  }
}
