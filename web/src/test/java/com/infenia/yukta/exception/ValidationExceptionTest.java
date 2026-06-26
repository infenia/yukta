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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationExceptionTest {

  @Test
  void testConstructorWithMessage() {
    ValidationException ex = new ValidationException("Invalid input");
    assertEquals("Invalid input", ex.getMessage());
    assertEquals(1, ex.getErrors().size());
    assertEquals("Invalid input", ex.getErrors().get(0));
  }

  @Test
  void testConstructorWithMultipleErrors() {
    List<String> errors = List.of("Field A is required", "Field B must be positive");
    ValidationException ex = new ValidationException("Validation failed", errors);
    assertEquals("Validation failed", ex.getMessage());
    assertEquals(2, ex.getErrors().size());
    assertEquals("Field A is required", ex.getErrors().get(0));
    assertEquals("Field B must be positive", ex.getErrors().get(1));
  }

  @Test
  void testConstructorWithNullErrors() {
    ValidationException ex = new ValidationException("Validation failed", null);
    assertEquals("Validation failed", ex.getMessage());
    assertEquals(1, ex.getErrors().size());
    assertEquals("Validation failed", ex.getErrors().get(0));
  }
}
