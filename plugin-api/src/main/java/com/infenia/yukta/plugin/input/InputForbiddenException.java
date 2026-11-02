// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.io.Serial;

/**
 * The sender may not answer or hand up this request. The message and {@link #rule()} name the rule
 * that failed, so the sender learns why and the denial can be counted by rule.
 *
 * @since 0.1.0-alpha.4
 */
public final class InputForbiddenException extends InputException {

  @Serial private static final long serialVersionUID = 1L;

  /** The rule that failed, for example {@code risk}. */
  private final String failedRule;

  /**
   * Creates the exception.
   *
   * @param requestId the request id
   * @param rule the rule that failed, for example {@code audience}, {@code risk} or {@code scope}
   * @param message what the sender may not do, and why
   */
  public InputForbiddenException(final String requestId, final String rule, final String message) {
    super(requestId, message);
    this.failedRule = rule;
  }

  /**
   * The rule that failed.
   *
   * @return the rule name
   */
  public String rule() {
    return failedRule;
  }
}
