// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.experimental.UtilityClass;

/**
 * A caller's regular expression over log lines, guarded against runaway patterns: the pattern is
 * short, each line is cut before it is matched, and one line may spend only a fixed time in the
 * regex engine. Shared by every reader that lets a client grep a log (node output, run search).
 */
@UtilityClass
public class GrepPattern {

  /** Longest text kept per line; also the longest a pattern is matched against. */
  public static final int MAX_LINE = 2000;

  /** Longest pattern accepted, against runaway regular expressions. */
  public static final int MAX_PATTERN = 200;

  /** Time one line may spend in the regex engine before the read is refused. */
  private static final long MATCH_BUDGET_NANOS = 50_000_000L;

  /**
   * Compiles a caller's pattern.
   *
   * @param grep the pattern
   * @return the compiled pattern
   * @throws IllegalArgumentException if it is longer than {@value #MAX_PATTERN} characters or not a
   *     valid regular expression
   */
  public static Pattern compile(final String grep) {
    if (grep.length() > MAX_PATTERN) {
      throw new IllegalArgumentException("grep is longer than " + MAX_PATTERN + " characters");
    }
    try {
      // The pattern is the caller's on purpose (a grep): it is at most 200 characters and each line
      // is matched within a time budget.
      return Pattern.compile(grep); // lgtm[java/regex-injection]
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          "grep is not a valid regular expression: " + e.getDescription(), e);
    }
  }

  /**
   * Whether a line contains a match, looking at its first {@value #MAX_LINE} characters only.
   *
   * @param pattern a pattern from {@link #compile}
   * @param line the line
   * @return whether it matches
   * @throws IllegalArgumentException if the line took longer than the time budget
   */
  public static boolean matches(final Pattern pattern, final String line) {
    return pattern.matcher(new Budgeted(cut(line), System.nanoTime() + MATCH_BUDGET_NANOS)).find();
  }

  /**
   * A line cut to {@value #MAX_LINE} characters.
   *
   * @param text the line
   * @return the line, or its first {@value #MAX_LINE} characters
   */
  public static String cut(final String text) {
    return text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text;
  }

  /**
   * Char sequence that aborts the regex engine once its deadline has passed (catastrophic
   * backtracking).
   */
  /* package */ record Budgeted(String text, long deadline) implements CharSequence {
    @Override
    public char charAt(final int index) {
      if (System.nanoTime() > deadline) {
        throw new IllegalArgumentException(
            "grep is too expensive to evaluate; simplify the pattern");
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
