// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import java.io.Serial;

/** Exception thrown when a filter condition fails to evaluate in strict mode. */
public class FilterEvaluationException extends WorkflowExecutionException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Constructs a new FilterEvaluationException with the specified message.
   *
   * @param message the detail message
   */
  public FilterEvaluationException(final String message) {
    super(message);
  }

  /**
   * Constructs a new FilterEvaluationException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause
   */
  public FilterEvaluationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
