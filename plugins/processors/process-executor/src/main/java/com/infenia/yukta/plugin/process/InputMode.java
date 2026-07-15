// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.Arrays;
import java.util.Locale;

/** How the input message is handed to the executed process. */
@SuppressWarnings("PMD.OnlyOneReturn")
public enum InputMode {
  /** The input message is not passed to the process (default). */
  NONE,
  /**
   * Message metadata is exported as {@code YUKTA_METADATA_*} environment variables and the payload
   * as {@code YUKTA_PAYLOAD} (String payloads as-is, other payloads JSON-serialized). Explicitly
   * configured {@code env} entries take precedence. Do NOT use with messages carrying secrets:
   * environment variables are visible to child processes and process listings.
   */
  ENV,
  /**
   * The input payload is written to the process standard input; String payloads are written as-is,
   * other payloads are JSON-serialized.
   */
  STDIN;

  /**
   * Parse a configuration value into an input mode, case-insensitively.
   *
   * @param value the raw configuration value, or null for the default
   * @return the parsed mode, or {@link #NONE} when value is null
   * @throws IllegalArgumentException if the value does not name a known mode
   */
  public static InputMode from(final Object value) {
    if (value == null) {
      return NONE;
    }
    try {
      return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown inputMode: " + value + " (expected one of " + Arrays.toString(values()) + ")",
          e);
    }
  }
}
