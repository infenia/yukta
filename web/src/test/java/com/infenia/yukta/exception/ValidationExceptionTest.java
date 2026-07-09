// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ValidationException. */
@NoArgsConstructor
class ValidationExceptionTest {

  /** Invalid input message for testing. */
  private static final String INVALID_INPUT = "Invalid input";

  /** Validation failed message for testing. */
  private static final String VALIDATION_FAILED = "Validation failed";

  /** Field A required error message for testing. */
  private static final String FIELD_A_REQUIRED = "Field A is required";

  /** Field B positive error message for testing. */
  private static final String FIELD_B_POSITIVE = "Field B must be positive";

  @Test
  void testConstructorWithMessage() {
    final ValidationException exception = new ValidationException(INVALID_INPUT);
    assertThat(exception.getMessage()).isEqualTo(INVALID_INPUT);
    assertThat(exception.getErrors()).hasSize(1).contains(INVALID_INPUT);
  }

  @Test
  void testConstructorWithMultipleErrors() {
    final List<String> errors = List.of(FIELD_A_REQUIRED, FIELD_B_POSITIVE);
    final ValidationException exception = new ValidationException(VALIDATION_FAILED, errors);
    assertThat(exception.getMessage()).isEqualTo(VALIDATION_FAILED);
    assertThat(exception.getErrors())
        .hasSize(2)
        .containsExactly(FIELD_A_REQUIRED, FIELD_B_POSITIVE);
  }

  @Test
  void testConstructorWithNullErrors() {
    final ValidationException exception = new ValidationException(VALIDATION_FAILED, null);
    assertThat(exception.getMessage()).isEqualTo(VALIDATION_FAILED);
    assertThat(exception.getErrors()).hasSize(1).contains(VALIDATION_FAILED);
  }
}
