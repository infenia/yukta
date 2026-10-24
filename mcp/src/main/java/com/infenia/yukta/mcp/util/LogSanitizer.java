// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Cleans a log line before it is handed to an agent: secrets are replaced, control characters
 * (including line breaks, so one entry stays one line) become spaces and an over-long line is cut.
 * The redaction patterns are patterns, not a guarantee: a secret in an unusual shape gets through.
 */
public final class LogSanitizer {

  /** What replaces a secret. */
  public static final String REDACTED = "[REDACTED]";

  /**
   * Patterns used when none are configured: bearer tokens, key=value secrets, common key shapes.
   */
  public static final List<String> DEFAULT_PATTERNS =
      List.of(
          "(?i)bearer\\s+[a-z0-9._~+/=-]{8,}",
          // name (maybe quoted), = or :, then a quoted value (to its closing quote) or an unquoted
          // one up to a delimiter or the next name=value, so several words are redacted whole
          "(?i)[\"']?(?:api[_-]?key|secret|password|passwd|token|authorization)[\"']?\\s*[=:]\\s*"
              + "(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"
              + "|(?:(?!\\s+[\\w.-]+=)[^,;&}\\]\"'])+)",
          "\\bsk-[A-Za-z0-9_-]{16,}",
          "\\bgh[pousr]_[A-Za-z0-9]{20,}",
          "\\bAKIA[0-9A-Z]{16}\\b");

  /** Line breaks and other control characters. */
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

  /** Compiled secret patterns. */
  private final List<Pattern> secrets;

  /** Longest line returned, in characters. */
  private final int maxLineChars;

  /**
   * Create a sanitizer.
   *
   * @param patterns regexes whose matches are replaced; null or empty uses the defaults
   * @param maxLineChars longest line returned; 0 or less means no cut
   */
  public LogSanitizer(final List<String> patterns, final int maxLineChars) {
    final List<String> source =
        patterns == null || patterns.isEmpty() ? DEFAULT_PATTERNS : patterns;
    this.secrets = source.stream().map(Pattern::compile).toList();
    this.maxLineChars = maxLineChars;
  }

  /**
   * Clean one line.
   *
   * @param line the formatted log line
   * @return the line without secrets or control characters, cut to the maximum length
   */
  public String clean(final String line) {
    String result = CONTROL.matcher(line).replaceAll(" ");
    for (final Pattern secret : secrets) {
      result = secret.matcher(result).replaceAll(REDACTED);
    }
    if (maxLineChars > 0 && result.length() > maxLineChars) {
      result = result.substring(0, maxLineChars) + "…[cut]";
    }
    return result;
  }
}
