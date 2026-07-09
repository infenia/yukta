// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegexPatternValidatorTest {

  @Test
  void testValidatePatternValid() {
    RegexPatternValidator.validatePattern(".*test.*");
  }

  @Test
  void testValidatePatternNull() {
    RegexPatternValidator.validatePattern(null);
  }

  @Test
  void testValidatePatternBlank() {
    RegexPatternValidator.validatePattern("   ");
  }

  @Test
  void testValidatePatternInvalid() {
    assertThrows(
        IllegalArgumentException.class, () -> RegexPatternValidator.validatePattern("[invalid"));
  }

  @Test
  void testMatchesPatternMatch() {
    assertTrue(RegexPatternValidator.matches("This is a test", ".*test.*"));
  }

  @Test
  void testMatchesPatternNoMatch() {
    assertFalse(RegexPatternValidator.matches("This is a test", ".*xyz.*"));
  }

  @Test
  void testMatchesPatternNull() {
    assertTrue(RegexPatternValidator.matches("text", null));
  }

  @Test
  void testMatchesPatternBlank() {
    assertTrue(RegexPatternValidator.matches("text", "   "));
  }

  @Test
  void testMatchesPatternInvalid() {
    assertThrows(
        IllegalArgumentException.class, () -> RegexPatternValidator.matches("text", "[invalid"));
  }
}
