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

/** Tests for SessionDetailsTest. */
@NoArgsConstructor
class SessionDetailsTest {

  /** First session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Second session ID test constant. */
  private static final String SESSION_ID_456 = "session-456";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_123;
    final List<String> workflowIds = List.of("workflow1", "workflow2");

    // When
    final SessionDetails details = new SessionDetails(sessionId, workflowIds);

    // Then
    assertThat(details.sessionId()).isEqualTo(sessionId);
    assertThat(details.workflowIds()).hasSize(2);
  }

  @Test
  void constructor_withNullWorkflowIds_convertsToEmptyList() {
    // Given-When
    final SessionDetails details = new SessionDetails(SESSION_ID_123, null);

    // Then
    assertThat(details.workflowIds()).isEmpty();
    assertThat(details.workflowIds()).isUnmodifiable();
  }

  @Test
  void workflowIds_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionDetails details = new SessionDetails(SESSION_ID_123, List.of("workflow1"));

    // When-Then
    assertThatThrownBy(() -> details.workflowIds().add("workflow2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final List<String> workflowIds = List.of("workflow1");
    final SessionDetails details1 = new SessionDetails(SESSION_ID_123, workflowIds);
    final SessionDetails details2 = new SessionDetails(SESSION_ID_123, workflowIds);

    // When-Then
    assertThat(details1).isEqualTo(details2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SessionDetails details1 = new SessionDetails(SESSION_ID_123, List.of());
    final SessionDetails details2 = new SessionDetails(SESSION_ID_456, List.of());

    // When-Then
    assertThat(details1).isNotEqualTo(details2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionDetails details = new SessionDetails(SESSION_ID_123, List.of());

    // When
    final String actual = details.toString();

    // Then
    assertThat(actual).contains("SessionDetails").contains(SESSION_ID_123);
  }
}
