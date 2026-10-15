// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import java.util.Map;
import lombok.experimental.UtilityClass;

/** Fail-fast config checks shared by plugins, so bad values are rejected in validateConfig. */
@UtilityClass
public class ConfigValidation {

  /** Config key: how many messages a node handles at once. */
  public static final String CONCURRENCY_KEY = "concurrency";

  /**
   * Reads an optional whole number greater than zero.
   *
   * @param config the plugin config
   * @param key the config key
   * @param defaultValue returned when the key is absent
   * @return the value, or the default
   * @throws IllegalArgumentException if present but not a positive whole number
   */
  public static long positiveLong(
      final Map<String, Object> config, final String key, final long defaultValue) {
    final Object raw = config.get(key);
    long result = defaultValue;
    if (raw != null) {
      if (raw instanceof Number num && isPositiveWhole(num.doubleValue())) {
        result = num.longValue();
      } else {
        throw new IllegalArgumentException(key + " must be a positive whole number, got: " + raw);
      }
    }
    return result;
  }

  /** Finite, whole, above zero and below 2^63, so {@code longValue()} cannot saturate. */
  private static boolean isPositiveWhole(final double value) {
    return Double.isFinite(value) && value == Math.rint(value) && value > 0 && value < 0x1p63;
  }

  /**
   * Reads an optional positive int.
   *
   * @param config the plugin config
   * @param key the config key
   * @param defaultValue returned when the key is absent
   * @return the value, or the default
   * @throws IllegalArgumentException if present but not a positive whole number within int range
   */
  public static int positiveInt(
      final Map<String, Object> config, final String key, final int defaultValue) {
    final long value = positiveLong(config, key, defaultValue);
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(key + " is too large: " + value);
    }
    return (int) value;
  }

  /**
   * Reads how many messages a node may handle at once.
   *
   * <p>Plugins that call something slow or costly per message pass this to {@code
   * flatMapSequential}, so a burst of messages cannot start hundreds of calls (Reactor's own
   * default is 256).
   *
   * @param config the plugin config
   * @param defaultValue the plugin's default when {@value #CONCURRENCY_KEY} is absent
   * @return the number of messages handled at once
   * @throws IllegalArgumentException if present but not a positive whole number
   */
  public static int concurrency(final Map<String, Object> config, final int defaultValue) {
    return positiveInt(config, CONCURRENCY_KEY, defaultValue);
  }

  /**
   * Reads a required non-blank string.
   *
   * @param config the plugin config
   * @param key the config key
   * @return the trimmed value
   * @throws IllegalArgumentException if missing or blank
   */
  public static String requiredString(final Map<String, Object> config, final String key) {
    final Object raw = config.get(key);
    if (raw == null || raw.toString().isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return raw.toString().trim();
  }
}
