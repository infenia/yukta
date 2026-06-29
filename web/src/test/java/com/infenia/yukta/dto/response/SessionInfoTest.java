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

import java.time.LocalDateTime;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionInfoTest. */
@NoArgsConstructor
class SessionInfoTest {

  /** Session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Active status constant. */
  private static final String ACTIVE = "ACTIVE";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_123;
    final int workflowCount = 5;
    final LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 10, 0);
    final LocalDateTime lastModified = LocalDateTime.of(2026, 1, 2, 11, 30);
    final String status = ACTIVE;

    // When
    final SessionInfo info =
        new SessionInfo(sessionId, workflowCount, createdAt, lastModified, status);

    // Then
    assertThat(info.sessionId()).isEqualTo(sessionId);
    assertThat(info.workflowCount()).isEqualTo(workflowCount);
    assertThat(info.createdAt()).isEqualTo(createdAt);
    assertThat(info.lastModified()).isEqualTo(lastModified);
    assertThat(info.status()).isEqualTo(status);
  }

  @Test
  void createdAt_localDateTime_returnsCorrectValue() {
    // Given
    final LocalDateTime expectedCreatedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
    final SessionInfo info =
        new SessionInfo(
            SESSION_ID_123, 5, expectedCreatedAt, LocalDateTime.of(2026, 1, 2, 11, 30), ACTIVE);

    // When
    final LocalDateTime actual = info.createdAt();

    // Then
    assertThat(actual).isEqualTo(expectedCreatedAt);
  }

  @Test
  void lastModified_localDateTime_returnsCorrectValue() {
    // Given
    final LocalDateTime expectedLastModified = LocalDateTime.of(2026, 1, 2, 11, 30);
    final SessionInfo info =
        new SessionInfo(
            SESSION_ID_123, 5, LocalDateTime.of(2026, 1, 1, 10, 0), expectedLastModified, ACTIVE);

    // When
    final LocalDateTime actual = info.lastModified();

    // Then
    assertThat(actual).isEqualTo(expectedLastModified);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 10, 0);
    final LocalDateTime lastModified = LocalDateTime.of(2026, 1, 2, 11, 30);
    final SessionInfo info1 = new SessionInfo(SESSION_ID_123, 5, createdAt, lastModified, ACTIVE);
    final SessionInfo info2 = new SessionInfo(SESSION_ID_123, 5, createdAt, lastModified, ACTIVE);

    // When-Then
    assertThat(info1).isEqualTo(info2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 10, 0);
    final LocalDateTime lastModified = LocalDateTime.of(2026, 1, 2, 11, 30);
    final SessionInfo info1 = new SessionInfo(SESSION_ID_123, 5, createdAt, lastModified, ACTIVE);
    final SessionInfo info2 = new SessionInfo("session-456", 5, createdAt, lastModified, ACTIVE);

    // When-Then
    assertThat(info1).isNotEqualTo(info2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionInfo info =
        new SessionInfo(
            "session-123",
            5,
            LocalDateTime.of(2026, 1, 1, 10, 0),
            LocalDateTime.of(2026, 1, 2, 11, 30),
            ACTIVE);

    // When
    final String actual = info.toString();

    // Then
    assertThat(actual).contains("SessionInfo").contains(SESSION_ID_123);
  }
}
