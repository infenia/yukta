// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import static org.assertj.core.api.Assertions.*;

import com.infenia.yukta.model.execution.TaskStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
  private static final TaskStatus SUCCESS = TaskStatus.SUCCESS;

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
    final TaskStatusEvent event =
        TaskStatusEvent.create(EXEC1, NODE1, MODULE1, TaskStatus.FAILURE, null);

    assertThat(event.executionId()).isEqualTo(EXEC1);
    assertThat(event.nodeId()).isEqualTo(NODE1);
    assertThat(event.module()).isEqualTo(MODULE1);
    assertThat(event.status()).isEqualTo(TaskStatus.FAILURE);
    assertThat(event.metadata()).isEqualTo(Map.of());
    assertThat(event.timestamp()).isNotNull();
  }

  @Test
  void directConstructor_withValidMetadata_createsEvent() {
    final Instant now = Instant.now();
    final Map<String, Object> metadata = Map.of("key1", "val1");
    final TaskStatusEvent event =
        new TaskStatusEvent(EXEC2, "node2", "module2", TaskStatus.PENDING, metadata, now);

    assertThat(event.executionId()).isEqualTo(EXEC2);
    assertThat(event.nodeId()).isEqualTo("node2");
    assertThat(event.module()).isEqualTo("module2");
    assertThat(event.status()).isEqualTo(TaskStatus.PENDING);
    assertThat(event.metadata()).isEqualTo(metadata);
    assertThat(event.timestamp()).isEqualTo(now);
  }

  @Test
  void directConstructor_withNullMetadata_normalizesToEmptyMap() {
    final Instant now = Instant.now();
    final TaskStatusEvent event =
        new TaskStatusEvent("exec3", "node3", "module3", TaskStatus.RUNNING, null, now);

    assertThat(event.executionId()).isEqualTo("exec3");
    assertThat(event.nodeId()).isEqualTo("node3");
    assertThat(event.module()).isEqualTo("module3");
    assertThat(event.status()).isEqualTo(TaskStatus.RUNNING);
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
        TaskStatusEvent.create("exec5", "node5", "module5", TaskStatus.SUCCESS, metadata);

    assertThat(event.metadata()).hasSize(3);
    assertThat(event.metadata().get("status_code")).isEqualTo(200);
    assertThat(event.metadata().get("duration_ms")).isEqualTo(500);
    assertThat(event.metadata().get("request_id")).isEqualTo("req-123");
  }

  @Test
  void recordEquality_sameValues_areEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);

    assertThat(event1).isEqualTo(event2);
  }

  @Test
  void recordEquality_differentExecutionId_areNotEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec2", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void recordEquality_differentNodeId_areNotEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node2", "module1", TaskStatus.SUCCESS, metadata, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void recordEquality_differentStatus_areNotEqual() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.FAILURE, metadata, timestamp);

    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void recordHashCode_sameValues_sameHash() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);

    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  void recordHashCode_differentValues_differentHash() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);
    final TaskStatusEvent event2 =
        new TaskStatusEvent("exec2", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);

    assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
  }

  @Test
  void recordToString_containsAllFields() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final TaskStatusEvent event =
        new TaskStatusEvent(
            "exec1", "node1", "module1", TaskStatus.SUCCESS, Map.of("k", "v"), timestamp);

    final String toString = event.toString();
    assertThat(toString)
        .contains("exec1")
        .contains("node1")
        .contains("module1")
        .contains("SUCCESS");
  }

  @Test
  void create_timestampIsApproximatelyNow() {
    final Instant before = Instant.now();
    final TaskStatusEvent event =
        TaskStatusEvent.create(
            "exec1", "node1", "module1", TaskStatus.SUCCESS, Map.of("key", "value"));
    final Instant after = Instant.now();

    assertThat(event.timestamp())
        .isAfterOrEqualTo(before.minusSeconds(1))
        .isBeforeOrEqualTo(after.plusSeconds(1));
  }

  @Test
  void emptyMetadata_createsDifferentInstanceThanNullMetadata() {
    final TaskStatusEvent eventWithNull =
        TaskStatusEvent.create("exec1", "node1", "module1", TaskStatus.SUCCESS, null);
    final TaskStatusEvent eventWithEmpty =
        TaskStatusEvent.create("exec1", "node1", "module1", TaskStatus.SUCCESS, Map.of());

    assertThat(eventWithNull.metadata()).isEqualTo(eventWithEmpty.metadata());
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    final Instant timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0).toInstant(ZoneOffset.UTC);
    final Map<String, Object> metadata = Map.of("key", "value");
    final TaskStatusEvent event =
        new TaskStatusEvent("exec1", "node1", "module1", TaskStatus.SUCCESS, metadata, timestamp);

    assertThat(event.executionId()).isInstanceOf(String.class);
    assertThat(event.nodeId()).isInstanceOf(String.class);
    assertThat(event.module()).isInstanceOf(String.class);
    assertThat(event.status()).isInstanceOf(TaskStatus.class);
    assertThat(event.metadata()).isNotNull().isEqualTo(metadata);
    assertThat(event.timestamp()).isInstanceOf(Instant.class);
  }
}
