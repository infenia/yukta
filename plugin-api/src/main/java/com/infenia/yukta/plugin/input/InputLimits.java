// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The size limits of an input request and the checks that apply them. Every check throws {@link
 * IllegalArgumentException} with a message that names the field, so a bad request is refused where
 * it is built.
 *
 * @since 0.1.0-alpha.4
 */
@UtilityClass
public class InputLimits {

  /** Longest prompt, in UTF-8 bytes. */
  public static final int PROMPT_MAX_BYTES = 4096;

  /** Longest serialised context of a request, in UTF-8 bytes. */
  public static final int CONTEXT_MAX_BYTES = 16_384;

  /** Longest free text of an answer, advice, reason or rationale, in UTF-8 bytes. */
  public static final int TEXT_MAX_BYTES = 8192;

  /** Longest serialised structured data of an answer, in UTF-8 bytes. */
  public static final int DATA_MAX_BYTES = 16_384;

  /** Most options a request can offer. */
  public static final int OPTIONS_MAX = 20;

  /** Longest id, key, decision or name, in characters. */
  public static final int ID_MAX_CHARS = 128;

  /** Longest requester label, in characters. */
  public static final int REQUESTER_MAX_CHARS = 64;

  /** Reads the size of a map as it would be sent, not as it is held. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Checks an id-like value: present, not blank and at most {@link #ID_MAX_CHARS} characters.
   *
   * @param field the field name for the message
   * @param value the value
   * @return the value
   */
  public static String requireId(final String field, final @Nullable String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (value.length() > ID_MAX_CHARS) {
      throw new IllegalArgumentException(field + " is longer than " + ID_MAX_CHARS + " characters");
    }
    return value;
  }

  /**
   * Checks a text: present, not blank and at most {@code maxBytes} bytes in UTF-8.
   *
   * @param field the field name for the message
   * @param value the value
   * @param maxBytes the limit
   * @return the value
   */
  public static String requireText(
      final String field, final @Nullable String value, final int maxBytes) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return optionalText(field, value, maxBytes);
  }

  /**
   * Checks a text that may be missing: at most {@code maxBytes} bytes in UTF-8.
   *
   * @param field the field name for the message
   * @param value the value, or {@code null}
   * @param maxBytes the limit
   * @return the value
   */
  public static @Nullable String optionalText(
      final String field, final @Nullable String value, final int maxBytes) {
    if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException(field + " is longer than " + maxBytes + " bytes");
    }
    return value;
  }

  /**
   * Checks that a map, written as JSON, is at most {@code maxBytes} bytes.
   *
   * @param field the field name for the message
   * @param value the map
   * @param maxBytes the limit
   */
  public static void requireWithin(
      final String field, final Map<String, Object> value, final int maxBytes) {
    final int size;
    try {
      size = JSON.writeValueAsBytes(value).length;
    } catch (JacksonException e) {
      throw new IllegalArgumentException(field + " cannot be written as JSON", e);
    }
    if (size > maxBytes) {
      throw new IllegalArgumentException(field + " is longer than " + maxBytes + " bytes");
    }
  }

  /**
   * Checks that a confidence is a probability.
   *
   * @param confidence the confidence, or {@code null}
   */
  public static void requireConfidence(final @Nullable Double confidence) {
    if (confidence != null && !(confidence >= 0.0 && confidence <= 1.0)) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
  }
}
