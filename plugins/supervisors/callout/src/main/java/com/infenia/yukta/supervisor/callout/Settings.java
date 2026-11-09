// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.callout;

import java.util.Map;
import lombok.experimental.UtilityClass;

/** Readers of webhook settings, with messages that name the key and never the value. */
@UtilityClass
public class Settings {

  /**
   * A required non-blank text.
   *
   * @param settings the settings
   * @param key the key
   * @return the text
   */
  public static String requiredText(final Map<String, Object> settings, final String key) {
    if (settings.get(key) instanceof final String text && !text.isBlank()) {
      return text.strip();
    }
    throw new IllegalArgumentException(key + " is required");
  }

  /**
   * A whole number from 1 to a ceiling.
   *
   * @param settings the settings
   * @param key the key
   * @param fallback the value when absent
   * @param max the ceiling
   * @return the number
   */
  public static int bounded(
      final Map<String, Object> settings, final String key, final int fallback, final int max) {
    final Object value = settings.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof final Number number
        && number.doubleValue() == Math.rint(number.doubleValue())
        && number.doubleValue() >= 1
        && number.doubleValue() <= max) {
      return number.intValue();
    }
    throw new IllegalArgumentException(key + " must be a whole number from 1 to " + max);
  }

  /**
   * A flag, false when absent.
   *
   * @param value true, false or their text
   * @return the flag
   */
  public static boolean flag(final Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof final Boolean flag) {
      return flag;
    }
    if (value instanceof final String text
        && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
      return Boolean.parseBoolean(text);
    }
    throw new IllegalArgumentException("review must be true or false");
  }
}
