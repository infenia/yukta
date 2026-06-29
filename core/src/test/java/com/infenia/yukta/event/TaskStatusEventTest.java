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
package com.infenia.yukta.event;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link TaskStatusEvent}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class TaskStatusEventTest {
  /** Key constant for test metadata. */
  private static final String KEY = "key";

  /** Value constant for test metadata. */
  private static final String VALUE = "value";

  /** Execution ID constant for tests. */
  private static final String EXEC1 = "exec1";

  /** Node ID constant for tests. */
  private static final String NODE1 = "node1";

  /** Module ID constant for tests. */
  private static final String MODULE1 = "module1";

  /** Success status constant. */
  private static final String SUCCESS = "SUCCESS";

  /** Secondary execution ID constant for tests. */
  private static final String EXEC2 = "exec2";

  @Test
  void create_withValidMetadata_returnsEventWithAllFields() {
    final Map<String, Object> metadata = Map.of(KEY, VALUE, "count", 42);
    final TaskStatusEvent event = TaskStatusEvent.create(EXEC1, NODE1, MODULE1, SUCCESS, metadata);

    assertThat(event.executionId()).isEqualTo(EXEC1);
    assertThat(event.nodeId()).isEqualTo(NODE1);
    assertThat(event.module()).isEqualTo(MODULE1);
    assertThat(event.status()).isEqualTo(SUCCESS);
    assertThat(event.metadata()).isEqualTo(metadata);
    assertThat(event.timestamp()).isNotNull();
  }

  @Test
  void create_withNullMetadata_normalizesToEmptyMap() {
    final TaskStatusEvent event = TaskStatusEvent.create(EXEC1, NODE1, MODULE1, "FAILED", null);

    assertThat(event.executionId()).isEqualTo(EXEC1);
    assertThat(event.nodeId()).isEqualTo(NODE1);
    assertThat(event.module()).isEqualTo(MODULE1);
    assertThat(event.status()).isEqualTo("FAILED");
    assertThat(event.metadata()).isEqualTo(Map.of());
    assertThat(event.timestamp()).isNotNull();
  }

  @Test
  void directConstructor_withValidMetadata_createsEvent() {
    final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
    final Map<String, Object> metadata = Map.of("key1", "val1");
    final TaskStatusEvent event =
        new TaskStatusEvent(EXEC2, "node2", "module2", "PENDING", metadata, now);

    assertThat(event.executionId()).isEqualTo(EXEC2);
    assertThat(event.nodeId()).isEqualTo("node2");
    assertThat(event.module()).isEqualTo("module2");
    assertThat(event.status()).isEqualTo("PENDING");
    assertThat(event.metadata()).isEqualTo(metadata);
    assertThat(event.timestamp()).isEqualTo(now);
  }

  @Test
  void directConstructor_withNullMetadata_normalizesToEmptyMap() {
    final LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
    final TaskStatusEvent event =
        new TaskStatusEvent("exec3", "node3", "module3", "RUNNING", null, now);

    assertThat(event.executionId()).isEqualTo("exec3");
    assertThat(event.nodeId()).isEqualTo("node3");
    assertThat(event.module()).isEqualTo("module3");
    assertThat(event.status()).isEqualTo("RUNNING");
    assertThat(event.metadata()).isEqualTo(Map.of());
    assertThat(event.timestamp()).isEqualTo(now);
  }

  @Test
  void metadata_isMutableMapInput_isConvertedToImmutable() {
    final Map<String, Object> mutableMetadata = new HashMap<>();
    mutableMetadata.put(KEY, VALUE);
    final TaskStatusEvent event =
        TaskStatusEvent.create("exec4", "node4", "module4", SUCCESS, mutableMetadata);

    assertThatThrownBy(() -> event.metadata().put("newKey", "newValue"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void create_withMultipleMetadataEntries_allEntriesPreserved() {
    final Map<String, Object> metadata =
        Map.of("status_code", 200, "duration_ms", 500, "request_id", "req-123");
    final TaskStatusEvent event =
        TaskStatusEvent.create("exec5", "node5", "module5", "COMPLETED", metadata);

    assertThat(event.metadata()).hasSize(3);
    assertThat(event.metadata().get("status_code")).isEqualTo(200);
    assertThat(event.metadata().get("duration_ms")).isEqualTo(500);
    assertThat(event.metadata().get("request_id")).isEqualTo("req-123");
  }

  @Test
  void recordEquality_sameValues_areEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  void recordEquality_differentExecutionId_areNotEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec2", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void recordEquality_differentNodeId_areNotEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node2", "module1", "SUCCESS", metadata, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void recordEquality_differentStatus_areNotEqual() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", "FAILED", metadata, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void recordHashCode_sameValues_sameHash() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  void recordHashCode_differentValues_differentHash() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec2", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
  }

  @Test
  void recordToString_containsAllFields() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final TaskStatusEvent event =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", Map.of("k", "v"), timestamp);

    final String toString = event.toString();
    assertThat(toString)
        .contains("exec1")
        .contains("node1")
        .contains("module1")
        .contains("SUCCESS");
  }

  @Test
  void create_timestampIsApproximatelyNow() {
    final LocalDateTime before = LocalDateTime.now(ZoneId.systemDefault());
    final TaskStatusEvent event =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", Map.of("key", "value"));
    final LocalDateTime after = LocalDateTime.now(ZoneId.systemDefault());

    assertThat(event.timestamp())
        .isAfterOrEqualTo(before.minusSeconds(1))
        .isBeforeOrEqualTo(after.plusSeconds(1));
  }

  @Test
  void emptyMetadata_createsDifferentInstanceThanNullMetadata() {
    final TaskStatusEvent eventWithNull =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", null);
    final TaskStatusEvent eventWithEmpty =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", Map.of());

    assertThat(eventWithNull.metadata()).isEqualTo(eventWithEmpty.metadata());
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    final LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertThat(event.executionId()).isInstanceOf(String.class);
    assertThat(event.nodeId()).isInstanceOf(String.class);
    assertThat(event.module()).isInstanceOf(String.class);
    assertThat(event.status()).isInstanceOf(String.class);
    assertThat(event.metadata()).isNotNull().isEqualTo(metadata);
    assertThat(event.timestamp()).isInstanceOf(LocalDateTime.class);
  }
}
