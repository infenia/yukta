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

import org.junit.jupiter.api.Test;

class ErrorExampleTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String error = "InvalidConfigError";
    String cause = "Missing required field in configuration";
    String resolution = "Ensure all required fields are provided";

    // When
    ErrorExample example = new ErrorExample(error, cause, resolution);

    // Then
    assertThat(example.error()).isEqualTo(error);
    assertThat(example.cause()).isEqualTo(cause);
    assertThat(example.resolution()).isEqualTo(resolution);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    ErrorExample example1 =
        new ErrorExample("InvalidConfigError", "Missing field", "Provide field");
    ErrorExample example2 =
        new ErrorExample("InvalidConfigError", "Missing field", "Provide field");

    // When-Then
    assertThat(example1).isEqualTo(example2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    ErrorExample example1 =
        new ErrorExample("InvalidConfigError", "Missing field", "Provide field");
    ErrorExample example2 =
        new ErrorExample("MissingArgumentError", "Missing field", "Provide field");

    // When-Then
    assertThat(example1).isNotEqualTo(example2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    ErrorExample example = new ErrorExample("InvalidConfigError", "Missing field", "Provide field");

    // When
    String actual = example.toString();

    // Then
    assertThat(actual).contains("ErrorExample").contains("InvalidConfigError");
  }
}
