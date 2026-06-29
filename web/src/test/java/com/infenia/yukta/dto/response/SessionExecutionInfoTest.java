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

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionExecutionInfoTest. */
@NoArgsConstructor
class SessionExecutionInfoTest {

  /** First session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Second session ID test constant. */
  private static final String SESSION_ID_456 = "session-456";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_123;
    final int activeExecutions = 5;
    final int totalWorkflows = 10;

    // When
    final SessionExecutionInfo info =
        new SessionExecutionInfo(sessionId, activeExecutions, totalWorkflows);

    // Then
    assertThat(info.sessionId()).isEqualTo(sessionId);
    assertThat(info.activeExecutions()).isEqualTo(activeExecutions);
    assertThat(info.totalWorkflows()).isEqualTo(totalWorkflows);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final SessionExecutionInfo info1 = new SessionExecutionInfo(SESSION_ID_123, 5, 10);
    final SessionExecutionInfo info2 = new SessionExecutionInfo(SESSION_ID_123, 5, 10);

    // When-Then
    assertThat(info1).isEqualTo(info2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SessionExecutionInfo info1 = new SessionExecutionInfo(SESSION_ID_123, 5, 10);
    final SessionExecutionInfo info2 = new SessionExecutionInfo(SESSION_ID_456, 5, 10);

    // When-Then
    assertThat(info1).isNotEqualTo(info2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionExecutionInfo info = new SessionExecutionInfo(SESSION_ID_123, 5, 10);

    // When
    final String actual = info.toString();

    // Then
    assertThat(actual).contains("SessionExecutionInfo").contains(SESSION_ID_123);
  }
}
