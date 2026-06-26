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

import org.junit.jupiter.api.Test;

class SessionExecutionInfoTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String sessionId = "session-123";
    int activeExecutions = 5;
    int totalWorkflows = 10;

    // When
    SessionExecutionInfo info =
        new SessionExecutionInfo(sessionId, activeExecutions, totalWorkflows);

    // Then
    assertThat(info.sessionId()).isEqualTo(sessionId);
    assertThat(info.activeExecutions()).isEqualTo(activeExecutions);
    assertThat(info.totalWorkflows()).isEqualTo(totalWorkflows);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    SessionExecutionInfo info1 = new SessionExecutionInfo("session-123", 5, 10);
    SessionExecutionInfo info2 = new SessionExecutionInfo("session-123", 5, 10);

    // When-Then
    assertThat(info1).isEqualTo(info2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    SessionExecutionInfo info1 = new SessionExecutionInfo("session-123", 5, 10);
    SessionExecutionInfo info2 = new SessionExecutionInfo("session-456", 5, 10);

    // When-Then
    assertThat(info1).isNotEqualTo(info2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    SessionExecutionInfo info = new SessionExecutionInfo("session-123", 5, 10);

    // When
    String actual = info.toString();

    // Then
    assertThat(actual).contains("SessionExecutionInfo").contains("session-123");
  }
}
