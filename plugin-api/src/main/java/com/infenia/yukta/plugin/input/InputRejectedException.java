// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.io.Serial;

/**
 * The request was refused, or an answer to it was not accepted (for example it did not match the
 * options or the schema).
 *
 * @since 0.1.0-alpha.4
 */
public final class InputRejectedException extends InputException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param requestId the request id, or the key when there is no request
   * @param message what happened
   */
  public InputRejectedException(final String requestId, final String message) {
    super(requestId, message);
  }
}
