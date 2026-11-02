// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Replaces secrets in text and in nested maps and lists. It is the one list of secret patterns and
 * secret key names in the server: the MCP log sanitizer and the input request redactor both use it.
 *
 * <p>Text is cleaned with a list of regular expressions. A map value is replaced whole when its key
 * looks like a secret ({@code password}, {@code token}, ...) or is one of the extra key names given
 * to the constructor.
 */
public final class SecretRedactor {

  /** What a secret becomes. */
  public static final String REDACTED = "[REDACTED]";

  /** The patterns used when none are configured. */
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

  /** Map keys whose value is a secret. */
  private static final String SECRET_KEY_REGEX =
      "(?i)api[_-]?key|secret|password|passwd|token|authorization";

  /** Deepest nesting walked; deeper values are replaced. */
  private static final int MAX_DEPTH = 32;

  /** The patterns for text. */
  private final List<Pattern> secrets;

  /** The keys whose value is a secret. */
  private final Pattern secretKey;

  /**
   * Creates a redactor.
   *
   * @param patterns the text patterns; null or empty means {@link #DEFAULT_PATTERNS}
   * @param extraKeys more key names whose value is a secret, matched whole and ignoring case
   */
  public SecretRedactor(
      final @Nullable List<String> patterns, final @Nullable Collection<String> extraKeys) {
    final List<String> source =
        patterns == null || patterns.isEmpty() ? DEFAULT_PATTERNS : patterns;
    this.secrets = source.stream().map(Pattern::compile).toList();
    final StringBuilder keys = new StringBuilder(SECRET_KEY_REGEX);
    if (extraKeys != null) {
      for (final String key : extraKeys) {
        if (key != null && !key.isBlank()) {
          keys.append("|^").append(Pattern.quote(key.strip())).append('$');
        }
      }
    }
    this.secretKey = Pattern.compile(keys.toString(), Pattern.CASE_INSENSITIVE);
  }

  /**
   * Replaces the secrets in a text.
   *
   * @param text the text
   * @return the text with each secret replaced by {@value #REDACTED}
   */
  public String redactText(final String text) {
    String result = text;
    for (final Pattern secret : secrets) {
      result = secret.matcher(result).replaceAll(REDACTED);
    }
    return result;
  }

  /**
   * Replaces the secrets in a value: a text, or the texts in a map or collection.
   *
   * @param value the value
   * @return a cleaned copy; other values are returned as they are
   */
  public Object redact(final Object value) {
    return redact(value, 0);
  }

  private Object redact(final Object value, final int depth) {
    return depth > MAX_DEPTH ? REDACTED : redactShallow(value, depth);
  }

  /**
   * Replaces the secrets in a map of strings.
   *
   * @param map the map
   * @return a cleaned copy with the same keys, in the same order
   */
  public Map<String, Object> redactMap(final Map<String, Object> map) {
    final SequencedMap<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, item) -> copy.put(key, redactEntry(key, item, 1)));
    return copy;
  }

  private Object redactShallow(final Object value, final int depth) {
    return switch (value) {
      case String text -> redactText(text);
      case Map<?, ?> map -> {
        final SequencedMap<Object, Object> copy = new LinkedHashMap<>();
        map.forEach(
            (key, item) ->
                copy.put(key, redactEntry(key == null ? null : key.toString(), item, depth + 1)));
        yield copy;
      }
      case Collection<?> items -> items.stream().map(item -> redact(item, depth + 1)).toList();
      case null, default -> value;
    };
  }

  private Object redactEntry(final @Nullable String key, final Object item, final int depth) {
    return key != null && secretKey.matcher(key).find() ? REDACTED : redact(item, depth);
  }
}
