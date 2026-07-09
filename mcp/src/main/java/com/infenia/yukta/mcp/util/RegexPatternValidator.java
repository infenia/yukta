// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.experimental.UtilityClass;

/**
 * Centralized regex pattern validation utility. Eliminates duplication of pattern validation logic
 * across the MCP tools.
 */
@UtilityClass
public class RegexPatternValidator {

  /**
   * Validate and compile a regex pattern.
   *
   * @param pattern the regex pattern to validate
   * @throws IllegalArgumentException if pattern is invalid
   */
  public static void validatePattern(final String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return;
    }
    try {
      Pattern.compile(pattern);
    } catch (final PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage(), e);
    }
  }

  /**
   * Check if text matches the given regex pattern.
   *
   * @param text the text to match
   * @param pattern the regex pattern
   * @return true if matches or pattern is null/blank, false otherwise
   * @throws IllegalArgumentException if pattern is invalid
   */
  @SuppressWarnings("PMD.OnlyOneReturn")
  public static boolean matches(final String text, final String pattern) {
    final boolean isBlankPattern = pattern == null || pattern.isBlank();
    if (isBlankPattern) {
      return true;
    }
    try {
      return Pattern.compile(pattern).matcher(text).find();
    } catch (final PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage(), e);
    }
  }
}
