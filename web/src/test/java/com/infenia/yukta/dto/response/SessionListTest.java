// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SessionListTest. */
@NoArgsConstructor
class SessionListTest {

  /** First session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Second session ID test constant. */
  private static final String SESSION_ID_456 = "session-456";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final List<String> sessionIds = List.of(SESSION_ID_123, SESSION_ID_456);

    // When
    final SessionList list = new SessionList(sessionIds);

    // Then
    assertThat(list.sessionIds()).hasSize(2);
    assertThat(list.sessionIds()).contains(SESSION_ID_123, SESSION_ID_456);
  }

  @Test
  void constructor_withNullSessionIds_convertsToEmptyList() {
    // Given-When
    final SessionList list = new SessionList(null);

    // Then
    assertThat(list.sessionIds()).isEmpty();
    assertThat(list.sessionIds()).isUnmodifiable();
  }

  @Test
  void sessionIds_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionList list = new SessionList(List.of(SESSION_ID_123));

    // When-Then
    assertThatThrownBy(() -> list.sessionIds().add("session-456"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final List<String> sessionIds = List.of("session-123");
    final SessionList list1 = new SessionList(sessionIds);
    final SessionList list2 = new SessionList(sessionIds);

    // When-Then
    assertThat(list1).isEqualTo(list2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SessionList list1 = new SessionList(List.of(SESSION_ID_123));
    final SessionList list2 = new SessionList(List.of(SESSION_ID_456));

    // When-Then
    assertThat(list1).isNotEqualTo(list2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionList list = new SessionList(List.of());

    // When
    final String actual = list.toString();

    // Then
    assertThat(actual).contains("SessionList");
  }
}
