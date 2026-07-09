// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import java.io.Serial;

/**
 * Standardized exception for errors occurring during workflow execution or plugin processing.
 *
 * <p>Concrete gateways and plugins should use this exception to maintain a technology-independent
 * error contract.
 */
public class WorkflowExecutionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Constructs a new WorkflowExecutionException with the specified detail message.
   *
   * @param message the detail message
   */
  public WorkflowExecutionException(final String message) {
    super(message);
  }

  /**
   * Constructs a new WorkflowExecutionException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause
   */
  public WorkflowExecutionException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
