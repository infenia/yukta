// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.Arrays;
import java.util.Locale;

/** Shape of the output message payload produced by the process executor plugin. */
@SuppressWarnings("PMD.OnlyOneReturn")
public enum OutputFormat {
  /**
   * Payload is a map describing the execution: exitCode, success, timedOut, durationMillis,
   * outputTruncated, plus stdout/stderr and the original input when configured.
   */
  STRUCTURED,
  /** Payload is the raw process stdout as a single string. */
  RAW,
  /**
   * Payload is the {@link #STRUCTURED} map plus an {@code output} entry containing the process
   * stdout parsed as JSON.
   */
  JSON,
  /** Payload of the input message is forwarded unchanged (legacy behavior). */
  PASSTHROUGH;

  /**
   * Parse a configuration value into an output format, case-insensitively.
   *
   * @param value the raw configuration value, or null for the default
   * @return the parsed format, or {@link #STRUCTURED} when value is null
   * @throws IllegalArgumentException if the value does not name a known format
   */
  public static OutputFormat from(final Object value) {
    if (value == null) {
      return STRUCTURED;
    }
    try {
      return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown outputFormat: " + value + " (expected one of " + Arrays.toString(values()) + ")",
          e);
    }
  }
}
