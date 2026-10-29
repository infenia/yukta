// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.experimental.UtilityClass;

/** Compiles and applies agent-supplied regex filters with bounds on pattern size and match time. */
@UtilityClass
@SuppressWarnings("PMD.CommentDefaultAccessModifier")
public class RegexPatternValidator {

  /** Longest accepted pattern; longer ones are rejected. */
  public static final int MAX_PATTERN_LENGTH = 200;

  /** Time one line may spend in the regex engine before the filter is rejected. */
  private static final long MATCH_BUDGET_NANOS = 50_000_000L;

  /**
   * Compile a filter pattern once so it can be reused for every line.
   *
   * @param pattern the regex, possibly null or blank
   * @return the compiled pattern, or null when no filter was given
   * @throws IllegalArgumentException if the pattern is too long or invalid
   */
  public static Pattern compile(final String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return null;
    }
    if (pattern.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException(
          "Regex pattern too long (max " + MAX_PATTERN_LENGTH + " characters).");
    }
    try {
      return Pattern.compile(pattern);
    } catch (final PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage(), e);
    }
  }

  /**
   * Check whether text matches a compiled pattern within the time budget.
   *
   * @param text the text to match
   * @param pattern the compiled pattern, or null to match everything
   * @return true if the text matches or there is no pattern
   * @throws IllegalArgumentException if matching exceeds the time budget (catastrophic
   *     backtracking)
   */
  public static boolean matches(final String text, final Pattern pattern) {
    return pattern == null || pattern.matcher(budgeted(text)).find();
  }

  /** Wrap text so the regex engine aborts once the per-line time budget is spent. */
  static CharSequence budgeted(final String text) {
    return new Budgeted(text, System.nanoTime() + MATCH_BUDGET_NANOS);
  }

  /** Char sequence that aborts the regex engine once its deadline has passed. */
  private record Budgeted(String text, long deadline) implements CharSequence {

    @Override
    public char charAt(final int index) {
      if (System.nanoTime() > deadline) {
        throw new IllegalArgumentException(
            "Regex filter is too expensive to evaluate; simplify the pattern.");
      }
      return text.charAt(index);
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return new Budgeted(text.substring(start, end), deadline);
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
