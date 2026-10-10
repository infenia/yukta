// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RegexPatternValidatorTest {

  @Test
  void testCompileValid() {
    assertTrue(RegexPatternValidator.compile(".*test.*").matcher("a test").matches());
  }

  @Test
  void testCompileNullAndBlankGiveNoFilter() {
    assertNull(RegexPatternValidator.compile(null));
    assertNull(RegexPatternValidator.compile("   "));
  }

  @Test
  void testCompileInvalid() {
    assertThrows(IllegalArgumentException.class, () -> RegexPatternValidator.compile("[invalid"));
  }

  @Test
  void testCompileTooLong() {
    final String tooLong = "a".repeat(RegexPatternValidator.MAX_PATTERN_LENGTH + 1);
    assertThrows(IllegalArgumentException.class, () -> RegexPatternValidator.compile(tooLong));
  }

  @Test
  void testMatches() {
    final Pattern pattern = Pattern.compile("test");
    assertTrue(RegexPatternValidator.matches("This is a test", pattern));
    assertFalse(RegexPatternValidator.matches("nothing here", pattern));
  }

  @Test
  void testNoPatternMatchesEverything() {
    assertTrue(RegexPatternValidator.matches("text", null));
  }

  @Test
  void testCatastrophicBacktrackingAborts() {
    final Pattern evil = Pattern.compile("(.*a){12}$");
    assertThrows(
        IllegalArgumentException.class,
        () -> RegexPatternValidator.matches("a".repeat(40) + "!", evil));
  }

  @Test
  void testBudgetedTextBehavesLikeTheOriginal() {
    final CharSequence text = RegexPatternValidator.budgeted("hello world");

    assertEquals(11, text.length());
    assertEquals("hello", text.subSequence(0, 5).toString());
    assertEquals("hello world", text.toString());
  }
}
