// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import com.infenia.yukta.logging.api.GrepPattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Compiles and applies agent-supplied regex filters. They run on {@link GrepPattern} (RE2/J, linear
 * time), so a filter cannot stall the server; only the pattern size is bounded.
 */
@UtilityClass
public class RegexPatternValidator {

  /** Longest accepted pattern; longer ones are rejected. */
  public static final int MAX_PATTERN_LENGTH = GrepPattern.MAX_PATTERN;

  /**
   * Compiles a filter.
   *
   * @param pattern the filter, in RE2 syntax, or null or blank for none
   * @return the compiled filter, or null for none
   * @throws IllegalArgumentException if it is too long or not a valid RE2 expression
   */
  public static @Nullable GrepPattern compile(@Nullable final String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return null;
    }
    if (pattern.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException(
          "Regex pattern too long (max " + MAX_PATTERN_LENGTH + " characters).");
    }
    try {
      return GrepPattern.compile(pattern);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage(), e);
    }
  }

  /**
   * Whether a text passes a filter: it contains a match anywhere, or there is no filter.
   *
   * @param text the text
   * @param pattern the filter, or null for none
   * @return whether it passes
   */
  public static boolean matches(final String text, @Nullable final GrepPattern pattern) {
    return pattern == null || pattern.find(text);
  }
}
