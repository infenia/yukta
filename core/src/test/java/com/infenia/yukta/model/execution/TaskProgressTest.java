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
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskProgressTest {

  @Test
  void constructor_withValidValues_createsRecordSuccessfully() {
    // Given
    String nodeId = "node-1";
    String module = "processor";
    String status = "RUNNING";
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T10:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T11:00:00");
    Map<String, Object> metadata = Map.of("key1", "value1");

    // When
    TaskProgress progress = new TaskProgress(nodeId, module, status, startTime, endTime, metadata);

    // Then
    assertThat(progress.nodeId()).isEqualTo(nodeId);
    assertThat(progress.module()).isEqualTo(module);
    assertThat(progress.status()).isEqualTo(status);
    assertThat(progress.startTime()).isEqualTo(startTime);
    assertThat(progress.endTime()).isEqualTo(endTime);
  }

  @Test
  void constructor_withNullMetadata_convertsToEmptyMap() {
    // Given
    String nodeId = "node-1";
    String module = "processor";
    String status = "COMPLETED";
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T10:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T11:00:00");

    // When
    TaskProgress progress = new TaskProgress(nodeId, module, status, startTime, endTime, null);

    // Then
    assertThat(progress.metadata()).isEmpty();
  }

  @Test
  void constructor_withNonNullMetadata_copiesMetadata() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> metadata = Map.of("key1", "value1", "key2", 42);
    LocalDateTime now = LocalDateTime.now();

    // When
    TaskProgress progress = new TaskProgress(nodeId, "processor", "RUNNING", now, now, metadata);

    // Then
    assertThat(progress.metadata()).containsEntry("key1", "value1").containsEntry("key2", 42);
  }

  @Test
  void constructor_modifyingOriginalMetadata_doesNotAffectRecord() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> originalMetadata = new HashMap<>();
    originalMetadata.put("key1", "value1");
    LocalDateTime now = LocalDateTime.now();
    TaskProgress progress =
        new TaskProgress(nodeId, "processor", "RUNNING", now, now, originalMetadata);

    // When
    originalMetadata.put("key2", "value2");

    // Then
    assertThat(progress.metadata()).containsKey("key1");
    assertThat(progress.metadata()).doesNotContainKey("key2");
  }

  @Test
  void constructor_withEmptyMetadata_createsSuccessfully() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> metadata = Map.of();
    LocalDateTime now = LocalDateTime.now();

    // When
    TaskProgress progress = new TaskProgress(nodeId, "processor", "RUNNING", now, now, metadata);

    // Then
    assertThat(progress.metadata()).isEmpty();
  }

  @Test
  void fields_withVariousValues_accessorsWork() {
    // Given
    String nodeId = "test-node";
    String module = "test-module";
    String status = "SUCCESS";
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T09:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T10:00:00");
    Map<String, Object> metadata = Map.of("result", "success");
    TaskProgress progress = new TaskProgress(nodeId, module, status, startTime, endTime, metadata);

    // When & Then
    assertThat(progress.nodeId()).isEqualTo(nodeId);
    assertThat(progress.module()).isEqualTo(module);
    assertThat(progress.status()).isEqualTo(status);
    assertThat(progress.startTime()).isEqualTo(startTime);
    assertThat(progress.endTime()).isEqualTo(endTime);
    assertThat(progress.metadata()).containsEntry("result", "success");
  }

  @Test
  void equality_sameFieldValues_isEqual() {
    // Given
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T10:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T11:00:00");
    Map<String, Object> metadata = Map.of("key1", "value1");
    TaskProgress progress1 =
        new TaskProgress("node-1", "processor", "RUNNING", startTime, endTime, metadata);
    TaskProgress progress2 =
        new TaskProgress("node-1", "processor", "RUNNING", startTime, endTime, metadata);

    // When & Then
    assertThat(progress1).isEqualTo(progress2);
  }

  @Test
  void equality_differentFieldValues_isNotEqual() {
    // Given
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T10:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T11:00:00");
    Map<String, Object> metadata = Map.of("key1", "value1");
    TaskProgress progress1 =
        new TaskProgress("node-1", "processor", "RUNNING", startTime, endTime, metadata);
    TaskProgress progress2 =
        new TaskProgress("node-2", "processor", "RUNNING", startTime, endTime, metadata);

    // When & Then
    assertThat(progress1).isNotEqualTo(progress2);
  }

  @Test
  void hashCode_sameFieldValues_sameHashCode() {
    // Given
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T10:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T11:00:00");
    Map<String, Object> metadata = Map.of("key1", "value1");
    TaskProgress progress1 =
        new TaskProgress("node-1", "processor", "RUNNING", startTime, endTime, metadata);
    TaskProgress progress2 =
        new TaskProgress("node-1", "processor", "RUNNING", startTime, endTime, metadata);

    // When & Then
    assertThat(progress1.hashCode()).isEqualTo(progress2.hashCode());
  }

  @Test
  void toString_recordWithValues_containsAllFields() {
    // Given
    LocalDateTime startTime = LocalDateTime.parse("2026-06-25T10:00:00");
    LocalDateTime endTime = LocalDateTime.parse("2026-06-25T11:00:00");
    TaskProgress progress =
        new TaskProgress("node-1", "processor", "RUNNING", startTime, endTime, Map.of());

    // When
    String result = progress.toString();

    // Then
    assertThat(result).contains("node-1").contains("processor").contains("RUNNING");
  }

  @Test
  void metadata_whenStored_isImmutable() {
    // Given
    LocalDateTime now = LocalDateTime.now();
    TaskProgress progress =
        new TaskProgress("node-1", "processor", "RUNNING", now, now, Map.of("key", "value"));

    // When & Then
    assertThatThrownBy(() -> progress.metadata().put("newKey", "newValue"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
