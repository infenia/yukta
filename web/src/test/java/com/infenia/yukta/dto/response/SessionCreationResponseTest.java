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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionCreationResponseTest. */
@NoArgsConstructor
class SessionCreationResponseTest {

  /** First session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Second session ID test constant. */
  private static final String SESSION_ID_456 = "session-456";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_123;
    final List<String> createdWorkflows = List.of("workflow1", "workflow2");
    final List<String> warnings = List.of("warning1");
    final boolean success = true;

    // When
    final SessionCreationResponse response =
        new SessionCreationResponse(sessionId, createdWorkflows, warnings, success);

    // Then
    assertThat(response.sessionId()).isEqualTo(sessionId);
    assertThat(response.createdWorkflows()).hasSize(2);
    assertThat(response.warnings()).hasSize(1);
    assertThat(response.success()).isTrue();
  }

  @Test
  void constructor_withEmptyCreatedWorkflows_createsUnmodifiableList() {
    // Given-When
    final SessionCreationResponse response =
        new SessionCreationResponse(SESSION_ID_123, List.of(), List.of(), false);

    // Then
    assertThat(response.createdWorkflows()).isEmpty();
    assertThat(response.createdWorkflows()).isUnmodifiable();
  }

  @Test
  void constructor_withEmptyWarnings_createsUnmodifiableList() {
    // Given-When
    final SessionCreationResponse response =
        new SessionCreationResponse(SESSION_ID_123, List.of(), List.of(), false);

    // Then
    assertThat(response.warnings()).isEmpty();
    assertThat(response.warnings()).isUnmodifiable();
  }

  @Test
  void createdWorkflows_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionCreationResponse response =
        new SessionCreationResponse(SESSION_ID_123, List.of("workflow1"), List.of(), true);

    // When-Then
    assertThatThrownBy(() -> response.createdWorkflows().add("workflow2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void warnings_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionCreationResponse response =
        new SessionCreationResponse(SESSION_ID_123, List.of(), List.of("warning1"), true);

    // When-Then
    assertThatThrownBy(() -> response.warnings().add("warning2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final List<String> workflows = List.of("workflow1");
    final List<String> warnings = List.of("warning1");
    final SessionCreationResponse response1 =
        new SessionCreationResponse(SESSION_ID_123, workflows, warnings, true);
    final SessionCreationResponse response2 =
        new SessionCreationResponse(SESSION_ID_123, workflows, warnings, true);

    // When-Then
    assertThat(response1).isEqualTo(response2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SessionCreationResponse response1 =
        new SessionCreationResponse(SESSION_ID_123, List.of(), List.of(), true);
    final SessionCreationResponse response2 =
        new SessionCreationResponse(SESSION_ID_456, List.of(), List.of(), true);

    // When-Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionCreationResponse response =
        new SessionCreationResponse(SESSION_ID_123, List.of(), List.of(), true);

    // When
    final String actual = response.toString();

    // Then
    assertThat(actual).contains("SessionCreationResponse").contains(SESSION_ID_123);
  }
}
