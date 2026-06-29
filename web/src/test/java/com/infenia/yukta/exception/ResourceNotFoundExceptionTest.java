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
