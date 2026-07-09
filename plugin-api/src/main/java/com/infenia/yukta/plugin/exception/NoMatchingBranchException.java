// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

/** Exception thrown when no matching branch is found and strict mode is enabled. */
public class NoMatchingBranchException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Constructor with message.
   *
   * @param message the error message
   */
  public NoMatchingBranchException(final String message) {
    super(message);
  }
}
