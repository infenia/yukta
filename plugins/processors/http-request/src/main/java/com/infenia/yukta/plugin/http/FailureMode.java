// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import java.util.Arrays;
import java.util.Locale;

/** How the http-request plugin reacts to a failed request (non-2xx status or transport error). */
@SuppressWarnings("PMD.OnlyOneReturn")
public enum FailureMode {
  /** Fail the workflow node with a WorkflowExecutionException (default). */
  ERROR,
  /** Emit the result message so downstream nodes can route on it. */
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
