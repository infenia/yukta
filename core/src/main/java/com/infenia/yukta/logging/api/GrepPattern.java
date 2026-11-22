// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * A caller's regular expression over log lines (a grep). It runs on RE2/J, a linear-time engine
 * without backtracking: matching costs at most in proportion to the pattern times the text, so no
 * pattern can stall the server (no catastrophic backtracking, no time budget needed). The price is
 * RE2 syntax: no back-references and no lookaround. Shared by every reader that lets a client grep
 * a log (node output, run search, MCP log filter).
 */
public final class GrepPattern {

  /** Longest text kept per line; also the longest a pattern is matched against. */
  public static final int MAX_LINE = 2000;

  /** Longest pattern accepted. */
  public static final int MAX_PATTERN = 200;

  private final Pattern pattern;

  private GrepPattern(final Pattern pattern) {
    this.pattern = pattern;
  }

  /**
   * Compiles a caller's pattern.
   *
   * @param grep the pattern, in RE2 syntax
   * @return the compiled pattern
   * @throws IllegalArgumentException if it is longer than {@value #MAX_PATTERN} characters or not a
   *     valid RE2 regular expression (back-references and lookaround are not)
   */
  public static GrepPattern compile(final String grep) {
    if (grep.length() > MAX_PATTERN) {
      throw new IllegalArgumentException("grep is longer than " + MAX_PATTERN + " characters");
    }
    try {
      return new GrepPattern(Pattern.compile(grep));
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          "grep is not a valid regular expression (RE2 syntax: no back-references or"
              + " lookaround): "
              + e.getDescription(),
          e);
    }
  }

  /**
   * Whether a line contains a match, looking at its first {@value #MAX_LINE} characters only.
   *
   * @param line the line
   * @return whether it matches
   */
  public boolean matches(final String line) {
    return find(cut(line));
  }

  /**
   * Whether a text contains a match anywhere.
   *
   * @param text the text
   * @return whether it matches
   */
  public boolean find(final String text) {
    return pattern.matcher(text).find();
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

  @Override
  public String toString() {
    return pattern.pattern();
  }
}
