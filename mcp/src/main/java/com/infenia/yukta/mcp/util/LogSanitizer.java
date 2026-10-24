// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
          "(?i)(?:api[_-]?key|secret|password|passwd|token|authorization)\\s*[=:]\\s*\\S+",
          "\\bsk-[A-Za-z0-9_-]{16,}",
          "\\bgh[pousr]_[A-Za-z0-9]{20,}",
          "\\bAKIA[0-9A-Z]{16}\\b");

  /** Names of map entries whose value is a secret. */
  private static final Pattern SECRET_KEY =
      Pattern.compile("(?i)api[_-]?key|secret|password|passwd|token|authorization");

  /** Deepest payload nesting {@link #redact(Object)} follows. */
  private static final int MAX_DEPTH = 32;

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
    return redact(value, 0);
  }

  private Object redact(final Object value, final int depth) {
    return depth > MAX_DEPTH ? REDACTED : redactShallow(value, depth);
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap") // a local copy, and its order is kept
  private Object redactShallow(final Object value, final int depth) {
    return switch (value) {
      case String text -> redactText(text);
      case Map<?, ?> map -> {
        final Map<Object, Object> copy = new LinkedHashMap<>();
        map.forEach(
            (key, item) ->
                copy.put(
                    key,
                    key != null && SECRET_KEY.matcher(key.toString()).find()
                        ? REDACTED
                        : redact(item, depth + 1)));
        yield copy;
      }
      case Collection<?> items -> items.stream().map(item -> redact(item, depth + 1)).toList();
      case null, default -> value;
    };
  }

  private String redactText(final String text) {
    String result = text;
    for (final Pattern secret : secrets) {
      result = secret.matcher(result).replaceAll(REDACTED);
    }
    return result;
  }
}
