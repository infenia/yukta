// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

/** A workflow start was refused because the server is at its execution limit or shutting down. */
public class ExecutionRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the start was refused
   */
  public ExecutionRejectedException(final String message) {
    super(message);
  }
}
