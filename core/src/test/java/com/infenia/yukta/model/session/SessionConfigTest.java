// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link SessionConfig}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseConcurrentHashMap",
  "PMD.LinguisticNaming"
})
class SessionConfigTest {

  @Test
  void constructor_nullTags_tagsReturnsEmptyMap() {
    // Given
    final String projectPath = "/path/to/project";
    final String initiator = "user1";
    final String initiatedTime = "2026-06-25T10:00:00Z";
    final String description = "Test session";

    // When
    final SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, null, description, "test");

    // Then
    assertThat(config.tags()).isEmpty();
  }

  @Test
  void constructor_nonNullTags_tagsReturnsCopyWithSameContent() {
    // Given
    final String projectPath = "/path/to/project";
    final String initiator = "user1";
    final String initiatedTime = "2026-06-25T10:00:00Z";
    final Map<String, String> tags = Map.of("env", "test", "version", "1.0");
    final String description = "Test session";

    // When
    final SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, tags, description, "test");

    // Then
    assertThat(config.tags()).containsEntry("env", "test").containsEntry("version", "1.0");
  }

  @Test
  void constructor_nonNullTags_returnsUnmodifiableMap() {
    // Given
    final String projectPath = "/path/to/project";
    final String initiator = "user1";
    final String initiatedTime = "2026-06-25T10:00:00Z";
    final Map<String, String> tags = Map.of("env", "test");
    final String description = "Test session";
    final SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, tags, description, "test");

    // When & Then
    assertThatThrownBy(() -> config.tags().put("key", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void constructor_nonNullTags_defensivelyCopiesMap() {
    // Given
    final String projectPath = "/path/to/project";
    final String initiator = "user1";
    final String initiatedTime = "2026-06-25T10:00:00Z";
    final Map<String, String> originalTags = new HashMap<>();
    originalTags.put("env", "test");
    final String description = "Test session";
    final SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, originalTags, description, "test");

    // When
    originalTags.put("newTag", "newValue");

    // Then
    assertThat(config.tags()).containsKey("env");
    assertThat(config.tags()).doesNotContainKey("newTag");
  }

  @Test
  void record_allFieldsAccessible() {
    // Given
    final String projectPath = "/path/to/project";
    final String initiator = "user1";
    final String initiatedTime = "2026-06-25T10:00:00Z";
    final Map<String, String> tags = Map.of("env", "test");
    final String description = "Test session";
    final SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, tags, description, "test");

    // When & Then
    assertThat(config.projectPath()).isEqualTo(projectPath);
    assertThat(config.initiator()).isEqualTo(initiator);
    assertThat(config.initiatedTime()).isEqualTo(initiatedTime);
    assertThat(config.tags()).containsEntry("env", "test");
    assertThat(config.description()).isEqualTo(description);
  }

  @Test
  void equality_sameFieldValues_isEqual() {
    // Given
    final Map<String, String> tags = Map.of("env", "test");
    final SessionConfig config1 =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", tags, "Test", "test");
    final SessionConfig config2 =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", tags, "Test", "test");

    // When & Then
    assertThat(config1).isEqualTo(config2);
  }

  @Test
  void equality_differentFieldValues_isNotEqual() {
    // Given
    final Map<String, String> tags = Map.of("env", "test");
    final SessionConfig config1 =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", tags, "Test", "test");
    final SessionConfig config2 =
        new SessionConfig("/path", "user2", "2026-06-25T10:00:00Z", tags, "Test", "test");

    // When & Then
    assertThat(config1).isNotEqualTo(config2);
  }

  @Test
  void toString_recordWithValues_containsAllFields() {
    // Given
    final SessionConfig config =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", Map.of(), "Test", "test");

    // When
    final String result = config.toString();

    // Then
    assertThat(result).contains("/path").contains("user1");
  }
}
