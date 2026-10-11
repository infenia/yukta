// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.Arrays;
import java.util.Locale;

/** How a plugin reacts to a failed operation (failed request, non-zero exit, SQL error, ...). */
@SuppressWarnings("PMD.OnlyOneReturn")
public enum FailureMode {
  /** Fail the workflow node (default). */
  ERROR,
  /** Emit a result so downstream nodes can route on it. */
  CONTINUE;

  /**
   * Parse a configuration value into a failure mode, case-insensitively.
   *
   * @param value the raw configuration value, or null for the default
   * @return the parsed mode, or {@link #ERROR} when value is null
   * @throws IllegalArgumentException if the value does not name a known mode
   */
  public static FailureMode from(final Object value) {
    if (value == null) {
      return ERROR;
    }
    try {
      return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown failureMode: " + value + " (expected one of " + Arrays.toString(values()) + ")",
          e);
    }
  }
}
