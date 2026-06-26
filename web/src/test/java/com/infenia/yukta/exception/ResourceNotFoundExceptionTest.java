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

import org.junit.jupiter.api.Test;

class ResourceNotFoundExceptionTest {

  @Test
  void testConstructorWithTypeAndId() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Session", "sess-123");
    assertEquals("Session not found: 'sess-123'", ex.getMessage());
    assertEquals("Session", ex.getResourceType());
    assertEquals("sess-123", ex.getResourceId());
  }

  @Test
  void testConstructorWithNullId() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Workflow", null);
    assertEquals("Workflow not found: 'unknown'", ex.getMessage());
  }

  @Test
  void testConstructorWithMessage() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Custom message");
    assertEquals("Custom message", ex.getMessage());
  }
}
