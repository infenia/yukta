// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.exception;

import java.util.List;
import lombok.Getter;

/** Exception thrown when validation fails for business logic. */
@Getter
public class ValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The list of validation error messages. */
  private final List<String> errors;

  /**
   * Constructor with single error message.
   *
   * @param message the validation error message
   */
  public ValidationException(final String message) {
    super(message);
    this.errors = List.of(message);
  }

  /**
   * Constructor with multiple error messages.
   *
   * @param message the validation error message
   * @param errors list of detailed validation errors
   */
  public ValidationException(final String message, final List<String> errors) {
    super(message);
    this.errors = errors != null ? List.copyOf(errors) : List.of(message);
  }
}
