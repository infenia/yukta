// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.exception;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ResourceNotFoundException. */
@NoArgsConstructor
class ResourceNotFoundExceptionTest {

  /** Session type identifier. */
  private static final String SESSION_TYPE = "Session";

  /** Session ID for testing. */
  private static final String SESSION_ID = "sess-123";

  /** Workflow type identifier. */
  private static final String WORKFLOW_TYPE = "Workflow";

  /** Custom message for testing. */
  private static final String CUSTOM_MESSAGE = "Custom message";

  @Test
  void testConstructorWithTypeAndId() {
    final ResourceNotFoundException exception =
        new ResourceNotFoundException(SESSION_TYPE, SESSION_ID);
    assertThat(exception.getMessage()).isEqualTo(SESSION_TYPE + " not found: '" + SESSION_ID + "'");
    assertThat(exception.getResourceType()).isEqualTo(SESSION_TYPE);
    assertThat(exception.getResourceId()).isEqualTo(SESSION_ID);
  }

  @Test
  void testConstructorWithNullId() {
    final ResourceNotFoundException exception = new ResourceNotFoundException(WORKFLOW_TYPE, null);
    assertThat(exception.getMessage()).isEqualTo(WORKFLOW_TYPE + " not found: 'unknown'");
  }

  @Test
  void testConstructorWithMessage() {
    final ResourceNotFoundException exception = new ResourceNotFoundException(CUSTOM_MESSAGE);
    assertThat(exception.getMessage()).isEqualTo(CUSTOM_MESSAGE);
  }
}
