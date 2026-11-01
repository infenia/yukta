// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import com.infenia.yukta.service.redact.SecretRedactor;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cleans a log line before it is handed to an agent: secrets are replaced, control characters
 * (including line breaks, so one entry stays one line) become spaces and an over-long line is cut.
 * The redaction patterns are patterns, not a guarantee: a secret in an unusual shape gets through.
 */
public final class LogSanitizer {

  /** What replaces a secret. */
  public static final String REDACTED = SecretRedactor.REDACTED;

  /**
   * Patterns used when none are configured: bearer tokens, key=value secrets, common key shapes.
   * The list is the server's one, kept in {@link SecretRedactor}.
   */
  public static final List<String> DEFAULT_PATTERNS = SecretRedactor.DEFAULT_PATTERNS;

  /** Line breaks and other control characters. */
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

  /** Replaces the secrets. */
  private final SecretRedactor redactor;

  /** Longest line returned, in characters. */
  private final int maxLineChars;

  /**
   * Create a sanitizer.
   *
   * @param patterns regexes whose matches are replaced; null or empty uses the defaults
   * @param maxLineChars longest line returned; 0 or less means no cut
   */
  public LogSanitizer(final List<String> patterns, final int maxLineChars) {
    this.redactor = new SecretRedactor(patterns, List.of());
    this.maxLineChars = maxLineChars;
  }

  /**
   * Clean one line.
   *
   * @param line the formatted log line
   * @return the line without secrets or control characters, cut to the maximum length
   */
  public String clean(final String line) {
    String result = redactText(CONTROL.matcher(line).replaceAll(" "));
    if (maxLineChars > 0 && result.length() > maxLineChars) {
      result = result.substring(0, maxLineChars) + "…[cut]";
    }
    return result;
  }

  /**
   * Remove secrets from a message payload of any shape, keeping line breaks and length: text is
   * redacted with the patterns, and the value of a map entry whose key names a secret is replaced.
   * Nesting deeper than 32 levels is replaced, so a cyclic structure cannot overflow the stack.
   *
   * @param value a string, map, list or any other payload
   * @return a copy with the secrets replaced; other types are returned as they are
   */
  public Object redact(final Object value) {
    return redactor.redact(value);
  }

  private String redactText(final String text) {
    return redactor.redactText(text);
  }
}
