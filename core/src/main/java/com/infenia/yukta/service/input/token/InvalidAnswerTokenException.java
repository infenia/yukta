// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.token;

import java.io.Serial;

/**
 * A one-time answer was refused. Unknown request, wrong tier, bad or expired token and a token used
 * before all look the same to the caller, so the endpoint cannot be used to find out which requests
 * exist.
 *
 * @since 0.1.0-alpha.4
 */
public final class InvalidAnswerTokenException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** What every refusal says. */
  public static final String MESSAGE = "Invalid or expired token";

  /** Creates the exception; it carries no stack trace and no detail. */
  public InvalidAnswerTokenException() {
    super(MESSAGE, null, false, false);
  }
}
