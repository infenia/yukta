// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

/** A supervisor refused a control command. */
public class ControlRejectedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the command was refused, safe to show to API clients
   */
  public ControlRejectedException(final String message) {
    super(message);
  }
}
