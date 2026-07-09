// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

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
