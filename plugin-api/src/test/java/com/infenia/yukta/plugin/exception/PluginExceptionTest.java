// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PluginExceptionTest {

  @Test
  void testExceptions() {
    Throwable cause = new RuntimeException("cause");

    FilterEvaluationException e1 = new FilterEvaluationException("m1");
    assertEquals("m1", e1.getMessage());
    FilterEvaluationException e1c = new FilterEvaluationException("m1", cause);
    assertEquals("m1", e1c.getMessage());
    assertEquals(cause, e1c.getCause());

    JoinTimeoutException e2 = new JoinTimeoutException("m2");
    assertEquals("m2", e2.getMessage());
    JoinTimeoutException e2c = new JoinTimeoutException("m2", cause);
    assertEquals("m2", e2c.getMessage());
    assertEquals(cause, e2c.getCause());

    NoMatchingBranchException e3 = new NoMatchingBranchException("m3");
    assertEquals("m3", e3.getMessage());

    WorkflowExecutionException e4 = new WorkflowExecutionException("m4");
    assertEquals("m4", e4.getMessage());
    WorkflowExecutionException e4c = new WorkflowExecutionException("m4", cause);
    assertEquals("m4", e4c.getMessage());
    assertEquals(cause, e4c.getCause());
  }
}
