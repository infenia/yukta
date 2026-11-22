// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for GrepPattern. */
@NoArgsConstructor
class GrepPatternTest {

  @Test
  void compile_acceptsValidPattern() {
    final GrepPattern pattern = GrepPattern.compile("FAIL(ED)?");

    assertThat(pattern.matches("Test > x FAILED")).isTrue();
    assertThat(pattern.matches("all good")).isFalse();
    assertThat(pattern).hasToString("FAIL(ED)?");
  }

  @Test
  void compile_refusesLongPattern() {
    final String tooLong = "a".repeat(GrepPattern.MAX_PATTERN + 1);

    assertThatThrownBy(() -> GrepPattern.compile(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longer than");
  }

  @Test
  void compile_refusesInvalidAndBacktrackingOnlySyntax() {
    assertThatThrownBy(() -> GrepPattern.compile("("))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("regular expression");
    // back-references and lookaround need backtracking, which RE2 does not do
    assertThatThrownBy(() -> GrepPattern.compile("(a)\\1"))
        .hasMessageContaining("no back-references");
    assertThatThrownBy(() -> GrepPattern.compile("a(?=b)"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void catastrophicPatterns_runInLinearTime() {
    // each of these takes exponential time on a backtracking engine for the line below
    final String line = "a".repeat(GrepPattern.MAX_LINE - 1) + "!";

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          for (int i = 0; i < 100; i++) {
            assertThat(GrepPattern.compile("(a+)+$").matches(line)).isFalse();
            assertThat(GrepPattern.compile("(a|aa)*b").matches(line)).isFalse();
            assertThat(GrepPattern.compile("(.*a){12}b").matches(line)).isFalse();
          }
        });
  }

  @Test
  void matches_looksOnlyAtKeptPartOfLine_findLooksEverywhere() {
    final String line = "x".repeat(GrepPattern.MAX_LINE) + "needle";

    assertThat(GrepPattern.compile("needle").matches(line)).isFalse();
    assertThat(GrepPattern.compile("needle").find(line)).isTrue();
  }

  @Test
  void cut_keepsShortLinesAndCutsLongOnes() {
    assertThat(GrepPattern.cut("short")).isEqualTo("short");
    assertThat(GrepPattern.cut("y".repeat(5000))).hasSize(GrepPattern.MAX_LINE);
  }
}
