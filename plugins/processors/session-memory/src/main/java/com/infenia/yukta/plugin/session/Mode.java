// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import java.util.Arrays;
import java.util.Locale;

/** What the session-memory plugin does with a message. */
public enum Mode {
  /** Attach the stored history to the payload and record the user's turn. */
  LOAD,
  /** Record the assistant's turn. */
  APPEND,
  /** Delete the stored history of the session. */
  DELETE;

  /**
   * Parse a configuration value, case-insensitively.
   *
   * @param value the raw configuration value
   * @return the mode
   * @throws IllegalArgumentException if value is null or unknown
   */
  public static Mode from(final Object value) {
    if (value == null) {
      throw new IllegalArgumentException("mode is mandatory");
    }
    try {
      return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown mode: " + value + " (expected one of " + Arrays.toString(values()) + ")", e);
    }
  }
}
