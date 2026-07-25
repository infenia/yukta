// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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

  /** Test data: name field. */
  private static final String NAME = "name";

  /** Test data: description field. */
  private static final String DESC = "desc";

  /** Test data: initiator field. */
  private static final String INITIATOR = "initiator";

  /** Test data: project path field. */
  private static final String PATH = "/path";

  /** Test data: tag1. */
  private static final String TAG1 = "tag1";

  /** Test data: workflow1. */
  private static final String WORKFLOW1 = "workflow1";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_123;
    final String name = "Test Session";
    final String description = "A test session";
    final String initiator = "test-user";
    final List<String> tags = List.of(TAG1, "tag2");
    final String projectPath = "/path/to/project";
    final List<String> workflowIds = List.of(WORKFLOW1, "workflow2");

    // When
    final SessionDetails details =
        new SessionDetails(sessionId, name, description, initiator, tags, projectPath, workflowIds);

    // Then
    assertThat(details.sessionId()).isEqualTo(sessionId);
    assertThat(details.name()).isEqualTo(name);
    assertThat(details.description()).isEqualTo(description);
    assertThat(details.initiator()).isEqualTo(initiator);
    assertThat(details.tags()).hasSize(2);
    assertThat(details.projectPath()).isEqualTo(projectPath);
    assertThat(details.workflowIds()).hasSize(2);
  }

  @Test
  void constructor_withNullTagsAndWorkflowIds_convertsToEmptyLists() {
    // Given-When
    final SessionDetails details =
        new SessionDetails(SESSION_ID_123, NAME, DESC, INITIATOR, null, PATH, null);

    // Then
    assertThat(details.tags()).isEmpty();
    assertThat(details.tags()).isUnmodifiable();
    assertThat(details.workflowIds()).isEmpty();
    assertThat(details.workflowIds()).isUnmodifiable();
  }

  @Test
  void tags_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionDetails details =
        new SessionDetails(
            SESSION_ID_123, NAME, DESC, INITIATOR, List.of(TAG1), PATH, List.of(WORKFLOW1));

    // When-Then
    assertThatThrownBy(() -> details.tags().add("tag2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void workflowIds_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final SessionDetails details =
        new SessionDetails(
            SESSION_ID_123, NAME, DESC, INITIATOR, List.of(), PATH, List.of(WORKFLOW1));

    // When-Then
    assertThatThrownBy(() -> details.workflowIds().add("workflow2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final SessionDetails details1 =
        new SessionDetails(
            SESSION_ID_123, NAME, DESC, INITIATOR, List.of(TAG1), PATH, List.of(WORKFLOW1));
    final SessionDetails details2 =
        new SessionDetails(
            SESSION_ID_123, NAME, DESC, INITIATOR, List.of(TAG1), PATH, List.of(WORKFLOW1));

    // When-Then
    assertThat(details1).isEqualTo(details2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SessionDetails details1 =
        new SessionDetails(SESSION_ID_123, NAME, DESC, INITIATOR, List.of(), PATH, List.of());
    final SessionDetails details2 =
        new SessionDetails(SESSION_ID_456, NAME, DESC, INITIATOR, List.of(), PATH, List.of());

    // When-Then
    assertThat(details1).isNotEqualTo(details2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SessionDetails details =
        new SessionDetails(SESSION_ID_123, NAME, DESC, INITIATOR, List.of(), PATH, List.of());

    // When
    final String actual = details.toString();

    // Then
    assertThat(actual).contains("SessionDetails").contains(SESSION_ID_123).contains(NAME);
  }
}
