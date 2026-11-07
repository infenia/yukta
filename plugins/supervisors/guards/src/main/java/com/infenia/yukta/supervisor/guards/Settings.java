// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/** Reads the settings of the guard types, with the messages of a bad value. */
@UtilityClass
public class Settings {

  /**
   * A whole number from 1 up.
   *
   * @param settings the settings
   * @param key the key
   * @param fallback the value when absent
   * @return the number
   * @throws IllegalArgumentException if it is not a whole number of at least 1
   */
  public static int positive(
      final Map<String, Object> settings, final String key, final int fallback) {
    final Object value = settings.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof final Number number
        && number.doubleValue() == Math.rint(number.doubleValue())
        && number.doubleValue() >= 1
        && number.doubleValue() <= Integer.MAX_VALUE) {
      return number.intValue();
    }
    throw new IllegalArgumentException(key + " must be a whole number of at least 1");
  }

  /**
   * An optional whole number from 1 up.
   *
   * @param settings the settings
   * @param key the key
   * @return the number, or null when absent
   */
  public static @Nullable Integer optionalPositive(
      final Map<String, Object> settings, final String key) {
    return settings.get(key) == null ? null : positive(settings, key, 1);
  }

  /**
   * One of the names of an enum, in any case.
   *
   * @param settings the settings
   * @param key the key
   * @param type the enum
   * @param fallback the value when absent
   * @param <E> the enum
   * @return the constant
   * @throws IllegalArgumentException if it names none
   */
  public static <E extends Enum<E>> E choice(
      final Map<String, Object> settings, final String key, final Class<E> type, final E fallback) {
    final Object value = settings.get(key);
    if (value == null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, value.toString().strip().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          key + " must be one of " + List.of(type.getEnumConstants()), e);
    }
  }

  /**
   * An optional text that, when given, is not blank.
   *
   * @param settings the settings
   * @param key the key
   * @return the text, or null when absent
   * @throws IllegalArgumentException if it is blank or not text
   */
  public static @Nullable String text(final Map<String, Object> settings, final String key) {
    final Object value = settings.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof final String text && !text.isBlank()) {
      return text;
    }
    throw new IllegalArgumentException(key + " must be non-blank text");
  }

  /**
   * An optional list.
   *
   * @param settings the settings
   * @param key the key
   * @return the list, or null when absent or empty
   * @throws IllegalArgumentException if it is not a list
   */
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null means "not given"
  public static @Nullable List<?> list(final Map<String, Object> settings, final String key) {
    final Object value = settings.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof final List<?> list) {
      return list.isEmpty() ? null : list;
    }
    throw new IllegalArgumentException(key + " must be a list");
  }
}
