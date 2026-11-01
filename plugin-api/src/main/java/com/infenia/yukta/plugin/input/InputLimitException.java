// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.io.Serial;

/**
 * The run asks too much: too many open requests, or asks too fast.
 *
 * @since 0.1.0-alpha.4
 */
public final class InputLimitException extends InputException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param requestId the request id, or the key when there is no request
   * @param message what happened
   */
  public InputLimitException(final String requestId, final String message) {
    super(requestId, message);
  }
}
