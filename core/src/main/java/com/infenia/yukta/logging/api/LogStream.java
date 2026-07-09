// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

/**
 * Enumeration of log stream types.
 *
 * <p>Categorizes log output by source/type for filtering and presentation.
 */
public enum LogStream {
  /** Standard output stream. */
  STDOUT,

  /** Standard error stream. */
  STDERR,

  /** Custom application-specific stream. */
  CUSTOM
}
