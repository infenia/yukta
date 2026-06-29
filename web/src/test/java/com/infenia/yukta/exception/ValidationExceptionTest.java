/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
