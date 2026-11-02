// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.io.Serial;

/**
 * Why an {@link InputGateway#ask} did not produce an answer. It carries the id of the request, or
 * the idempotency key when no request was made.
 *
 * @since 0.1.0-alpha.4
 */
public class InputException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** The request id, or the key of a request that was never made. */
  private final String subject;

  /**
   * Creates the exception.
   *
   * @param requestId the request id, or the key when there is no request
   * @param message what happened; shown to the plugin and possibly to a model, so it must not
   *     repeat a secret
   */
  protected InputException(final String requestId, final String message) {
    super(message);
    this.subject = requestId;
  }

  /**
   * The request the failure belongs to.
   *
   * @return the request id, or the idempotency key when no request was made
   */
  public String requestId() {
    return subject;
  }
}
