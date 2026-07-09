// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

/** Exception thrown when a join operation times out and strict mode is enabled. */
public class JoinTimeoutException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Constructor with message.
   *
   * @param message the error message
   */
  public JoinTimeoutException(final String message) {
    super(message);
  }

  /**
   * Constructor with message and cause.
   *
   * @param message the error message
   * @param cause the cause
   */
  public JoinTimeoutException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
