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
package com.infenia.jagratha.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JoinTimeoutExceptionTest {

  @Test
  void testExceptionMessage() {
    JoinTimeoutException ex = new JoinTimeoutException("timeout");
    assertEquals("timeout", ex.getMessage());
  }

  @Test
  void testExceptionMessageAndCause() {
    RuntimeException cause = new RuntimeException("cause");
    JoinTimeoutException ex = new JoinTimeoutException("timeout", cause);
    assertEquals("timeout", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }
}
