// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

/**
 * The request is valid but the thing it targets is in a state that does not allow it (for example
 * resuming a run that was not aborted). It extends {@link IllegalStateException} so existing
 * callers keep working, while the web layer can answer 409 instead of 500.
 */
public class ConflictException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the request cannot be applied, safe to show to API clients
   */
  public ConflictException(final String message) {
    super(message);
  }
}
