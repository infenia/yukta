// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.logging.api.GrepPattern;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for RegexPatternValidator. */
@NoArgsConstructor
class RegexPatternValidatorTest {

  @Test
  void testCompileValid() {
    assertTrue(RegexPatternValidator.matches("a test", RegexPatternValidator.compile(".*test.*")));
  }

  @Test
  void testCompileNullAndBlankGiveNoFilter() {
    assertNull(RegexPatternValidator.compile(null));
    assertNull(RegexPatternValidator.compile("   "));
  }

  @Test
  void testCompileInvalid() {
    final IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> RegexPatternValidator.compile("[invalid"));
    assertTrue(error.getMessage().startsWith("Invalid regex pattern"));
    assertThrows(IllegalArgumentException.class, () -> RegexPatternValidator.compile("(a)\\1"));
  }

  @Test
  void testCompileTooLong() {
    final String tooLong = "a".repeat(RegexPatternValidator.MAX_PATTERN_LENGTH + 1);
    final IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> RegexPatternValidator.compile(tooLong));
    assertTrue(error.getMessage().startsWith("Regex pattern too long"));
  }

  @Test
  void testMatches() {
    final GrepPattern pattern = RegexPatternValidator.compile("test");
    assertTrue(RegexPatternValidator.matches("This is a test", pattern));
    assertFalse(RegexPatternValidator.matches("nothing here", pattern));
  }

  @Test
  void testNoPatternMatchesEverything() {
    assertTrue(RegexPatternValidator.matches("text", null));
  }

  @Test
  void testCatastrophicPatternRunsInLinearTime() {
    final GrepPattern pattern = RegexPatternValidator.compile("(.*a){12}$");
    final String text = "a".repeat(5_000) + "!";
    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> assertFalse(RegexPatternValidator.matches(text, pattern)));
  }
}
