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
package com.infenia.yukta.model.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionConfigTest {

  @Test
  void constructor_nullTags_tagsReturnsEmptyMap() {
    // Given
    String projectPath = "/path/to/project";
    String initiator = "user1";
    String initiatedTime = "2026-06-25T10:00:00Z";
    String description = "Test session";

    // When
    SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, null, description);

    // Then
    assertThat(config.tags()).isEmpty();
  }

  @Test
  void constructor_nonNullTags_tagsReturnsCopyWithSameContent() {
    // Given
    String projectPath = "/path/to/project";
    String initiator = "user1";
    String initiatedTime = "2026-06-25T10:00:00Z";
    Map<String, String> tags = Map.of("env", "test", "version", "1.0");
    String description = "Test session";

    // When
    SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, tags, description);

    // Then
    assertThat(config.tags()).containsEntry("env", "test").containsEntry("version", "1.0");
  }

  @Test
  void constructor_nonNullTags_returnsUnmodifiableMap() {
    // Given
    String projectPath = "/path/to/project";
    String initiator = "user1";
    String initiatedTime = "2026-06-25T10:00:00Z";
    Map<String, String> tags = Map.of("env", "test");
    String description = "Test session";
    SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, tags, description);

    // When & Then
    assertThatThrownBy(() -> config.tags().put("key", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void constructor_nonNullTags_defensivelyCopiesMap() {
    // Given
    String projectPath = "/path/to/project";
    String initiator = "user1";
    String initiatedTime = "2026-06-25T10:00:00Z";
    Map<String, String> originalTags = new HashMap<>();
    originalTags.put("env", "test");
    String description = "Test session";
    SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, originalTags, description);

    // When
    originalTags.put("newTag", "newValue");

    // Then
    assertThat(config.tags()).containsKey("env");
    assertThat(config.tags()).doesNotContainKey("newTag");
  }

  @Test
  void record_allFieldsAccessible() {
    // Given
    String projectPath = "/path/to/project";
    String initiator = "user1";
    String initiatedTime = "2026-06-25T10:00:00Z";
    Map<String, String> tags = Map.of("env", "test");
    String description = "Test session";
    SessionConfig config =
        new SessionConfig(projectPath, initiator, initiatedTime, tags, description);

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
    Map<String, String> tags = Map.of("env", "test");
    SessionConfig config1 =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", tags, "Test");
    SessionConfig config2 =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", tags, "Test");

    // When & Then
    assertThat(config1).isEqualTo(config2);
  }

  @Test
  void equality_differentFieldValues_isNotEqual() {
    // Given
    Map<String, String> tags = Map.of("env", "test");
    SessionConfig config1 =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", tags, "Test");
    SessionConfig config2 =
        new SessionConfig("/path", "user2", "2026-06-25T10:00:00Z", tags, "Test");

    // When & Then
    assertThat(config1).isNotEqualTo(config2);
  }

  @Test
  void toString_recordWithValues_containsAllFields() {
    // Given
    SessionConfig config =
        new SessionConfig("/path", "user1", "2026-06-25T10:00:00Z", Map.of(), "Test");

    // When
    String result = config.toString();

    // Then
    assertThat(result).contains("/path").contains("user1");
  }
}
