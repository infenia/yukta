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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskStatusEventTest {

  @Test
  void create_withValidMetadata_returnsEventWithAllFields() {
    Map<String, Object> metadata = Map.of("key", "value", "count", 42);
    TaskStatusEvent event =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", metadata);

    assertEquals("exec1", event.executionId());
    assertEquals("node1", event.nodeId());
    assertEquals("module1", event.module());
    assertEquals("SUCCESS", event.status());
    assertEquals(metadata, event.metadata());
    assertNotNull(event.timestamp());
  }

  @Test
  void create_withNullMetadata_normalizesToEmptyMap() {
    TaskStatusEvent event = TaskStatusEvent.create("exec1", "node1", "module1", "FAILED", null);

    assertEquals("exec1", event.executionId());
    assertEquals("node1", event.nodeId());
    assertEquals("module1", event.module());
    assertEquals("FAILED", event.status());
    assertEquals(Map.of(), event.metadata());
    assertNotNull(event.timestamp());
  }

  @Test
  void directConstructor_withValidMetadata_createsEvent() {
    LocalDateTime now = LocalDateTime.now();
    Map<String, Object> metadata = Map.of("key1", "val1");
    TaskStatusEvent event =
        new TaskStatusEvent("exec2", "node2", "module2", "PENDING", metadata, now);

    assertEquals("exec2", event.executionId());
    assertEquals("node2", event.nodeId());
    assertEquals("module2", event.module());
    assertEquals("PENDING", event.status());
    assertEquals(metadata, event.metadata());
    assertEquals(now, event.timestamp());
  }

  @Test
  void directConstructor_withNullMetadata_normalizesToEmptyMap() {
    LocalDateTime now = LocalDateTime.now();
    TaskStatusEvent event = new TaskStatusEvent("exec3", "node3", "module3", "RUNNING", null, now);

    assertEquals("exec3", event.executionId());
    assertEquals("node3", event.nodeId());
    assertEquals("module3", event.module());
    assertEquals("RUNNING", event.status());
    assertEquals(Map.of(), event.metadata());
    assertEquals(now, event.timestamp());
  }

  @Test
  void metadata_isMutableMapInput_isConvertedToImmutable() {
    Map<String, Object> mutableMetadata = new HashMap<>();
    mutableMetadata.put("key", "value");
    TaskStatusEvent event =
        TaskStatusEvent.create("exec4", "node4", "module4", "SUCCESS", mutableMetadata);

    assertThrows(
        UnsupportedOperationException.class, () -> event.metadata().put("newKey", "newValue"));
  }

  @Test
  void create_withMultipleMetadataEntries_allEntriesPreserved() {
    Map<String, Object> metadata =
        Map.of("status_code", 200, "duration_ms", 500, "request_id", "req-123");
    TaskStatusEvent event =
        TaskStatusEvent.create("exec5", "node5", "module5", "COMPLETED", metadata);

    assertEquals(3, event.metadata().size());
    assertEquals(200, event.metadata().get("status_code"));
    assertEquals(500, event.metadata().get("duration_ms"));
    assertEquals("req-123", event.metadata().get("request_id"));
  }

  @Test
  void recordEquality_sameValues_areEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertEquals(event1, event2);
  }

  @Test
  void recordEquality_differentExecutionId_areNotEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    TaskStatusEvent event2 =
        new TaskStatusEvent("exec2", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void recordEquality_differentNodeId_areNotEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node2", "module1", "SUCCESS", metadata, timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void recordEquality_differentStatus_areNotEqual() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", "FAILED", metadata, timestamp);

    assertNotEquals(event1, event2);
  }

  @Test
  void recordHashCode_sameValues_sameHash() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    TaskStatusEvent event2 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertEquals(event1.hashCode(), event2.hashCode());
  }

  @Test
  void recordHashCode_differentValues_differentHash() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event1 =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);
    TaskStatusEvent event2 =
        new TaskStatusEvent("exec2", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertNotEquals(event1.hashCode(), event2.hashCode());
  }

  @Test
  void recordToString_containsAllFields() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    TaskStatusEvent event =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", Map.of("k", "v"), timestamp);

    String toString = event.toString();
    assertTrue(toString.contains("exec1"));
    assertTrue(toString.contains("node1"));
    assertTrue(toString.contains("module1"));
    assertTrue(toString.contains("SUCCESS"));
  }

  @Test
  void create_timestampIsApproximatelyNow() {
    LocalDateTime before = LocalDateTime.now();
    TaskStatusEvent event =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", Map.of("key", "value"));
    LocalDateTime after = LocalDateTime.now();

    assertTrue(
        !event.timestamp().isBefore(before.minusSeconds(1))
            && !event.timestamp().isAfter(after.plusSeconds(1)));
  }

  @Test
  void emptyMetadata_createsDifferentInstanceThanNullMetadata() {
    TaskStatusEvent eventWithNull =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", null);
    TaskStatusEvent eventWithEmpty =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", Map.of());

    assertEquals(eventWithNull.metadata(), eventWithEmpty.metadata());
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 6, 21, 10, 30, 0);
    Map<String, Object> metadata = Map.of("key", "value");
    TaskStatusEvent event =
        new TaskStatusEvent("exec1", "node1", "module1", "SUCCESS", metadata, timestamp);

    assertTrue(event.executionId() instanceof String);
    assertTrue(event.nodeId() instanceof String);
    assertTrue(event.module() instanceof String);
    assertTrue(event.status() instanceof String);
    assertNotNull(event.metadata());
    assertEquals(metadata, event.metadata());
    assertTrue(event.timestamp() instanceof LocalDateTime);
  }
}
