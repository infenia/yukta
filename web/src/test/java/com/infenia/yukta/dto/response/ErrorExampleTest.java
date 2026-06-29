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
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ErrorExampleTest. */
@NoArgsConstructor
class ErrorExampleTest {

  /** Invalid config error constant. */
  private static final String INVALID_CONFIG_ERROR = "InvalidConfigError";

  /** Missing field constant. */
  private static final String MISSING_FIELD = "Missing field";

  /** Provide field constant. */
  private static final String PROVIDE_FIELD = "Provide field";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String error = "InvalidConfigError";
    final String cause = "Missing required field in configuration";
    final String resolution = "Ensure all required fields are provided";

    // When
    final ErrorExample example = new ErrorExample(error, cause, resolution);

    // Then
    assertThat(example.error()).isEqualTo(error);
    assertThat(example.cause()).isEqualTo(cause);
    assertThat(example.resolution()).isEqualTo(resolution);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final ErrorExample example1 =
        new ErrorExample(INVALID_CONFIG_ERROR, MISSING_FIELD, PROVIDE_FIELD);
    final ErrorExample example2 =
        new ErrorExample(INVALID_CONFIG_ERROR, MISSING_FIELD, PROVIDE_FIELD);

    // When-Then
    assertThat(example1).isEqualTo(example2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final ErrorExample example1 =
        new ErrorExample(INVALID_CONFIG_ERROR, MISSING_FIELD, PROVIDE_FIELD);
    final ErrorExample example2 =
        new ErrorExample("MissingArgumentError", MISSING_FIELD, PROVIDE_FIELD);

    // When-Then
    assertThat(example1).isNotEqualTo(example2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final ErrorExample example =
        new ErrorExample(INVALID_CONFIG_ERROR, MISSING_FIELD, PROVIDE_FIELD);

    // When
    final String actual = example.toString();

    // Then
    assertThat(actual).contains("ErrorExample").contains(INVALID_CONFIG_ERROR);
  }
}
