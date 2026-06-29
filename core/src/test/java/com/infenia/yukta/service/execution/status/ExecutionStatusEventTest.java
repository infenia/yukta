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
package com.infenia.yukta.service.execution.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.LinguisticNaming",
  "PMD.UseConcurrentHashMap"
})
class ExecutionStatusEventTest {

  @Test
  void constructor_allFieldsProvided_preservesAllValues() {
    // Given
    final String executionId = "exec-123";
    final String nodeId = "node-456";
    final String workflowId = "workflow-789";
    final String sessionId = "session-012";
    final String status = "RUNNING";
    final String module = "core";
    final Map<String, Object> metadata = Map.of("key1", "value1", "key2", 42);
    final Throwable error = null;
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");

    // When
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            executionId, nodeId, workflowId, sessionId, status, module, metadata, error, timestamp);

    // Then
    assertThat(event.executionId()).isEqualTo(executionId);
    assertThat(event.nodeId()).isEqualTo(nodeId);
    assertThat(event.workflowId()).isEqualTo(workflowId);
    assertThat(event.sessionId()).isEqualTo(sessionId);
    assertThat(event.status()).isEqualTo(status);
    assertThat(event.module()).isEqualTo(module);
    assertThat(event.metadata()).isEqualTo(metadata);
    assertThat(event.error()).isNull();
    assertThat(event.timestamp()).isEqualTo(timestamp);
  }

  @Test
  void constructor_withNullableMetadata_acceptsNull() {
    // Given
    final String executionId = "exec-123";
    final String nodeId = "node-456";
    final String workflowId = "workflow-789";
    final String sessionId = "session-012";
    final String status = "RUNNING";
    final String module = "core";
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");

    // When
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            executionId, nodeId, workflowId, sessionId, status, module, null, null, timestamp);

    // Then
    assertThat(event.metadata()).isNull();
  }

  @Test
  void constructor_withNullableError_acceptsNull() {
    // Given
    final String executionId = "exec-123";
    final String nodeId = "node-456";
    final String workflowId = "workflow-789";
    final String sessionId = "session-012";
    final String status = "FAILURE";
    final String module = "core";
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");

    // When
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            executionId, nodeId, workflowId, sessionId, status, module, null, null, timestamp);

    // Then
    assertThat(event.error()).isNull();
  }

  @Test
  void constructor_withMetadataMap_preservesContents() {
    // Given
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("executionTime", 1234L);
    metadata.put("retryCount", 3);
    metadata.put("details", "Operation completed successfully");
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");

    // When
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "SUCCESS",
            "core",
            metadata,
            null,
            timestamp);

    // Then
    assertThat(event.metadata()).isNotNull();
    assertThat(event.metadata()).containsEntry("executionTime", 1234L);
    assertThat(event.metadata()).containsEntry("retryCount", 3);
    assertThat(event.metadata()).containsEntry("details", "Operation completed successfully");
  }

  @Test
  void constructor_withThrowable_preservesError() {
    // Given
    final RuntimeException exception = new RuntimeException("Test error");
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");

    // When
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "FAILURE",
            "core",
            null,
            exception,
            timestamp);

    // Then
    assertThat(event.error()).isNotNull();
    assertThat(event.error()).isInstanceOf(RuntimeException.class);
    assertThat(event.error()).hasMessage("Test error");
  }

  @Test
  void record_sameFieldValues_areEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isEqualTo(event2);
  }

  @Test
  void record_differentExecutionId_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-999",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentNodeId_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-999",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentWorkflowId_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-999",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentSessionId_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-999",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentStatus_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "SUCCESS",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentModule_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "mcp",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    // Given
    final Instant timestamp1 = Instant.parse("2026-06-25T10:00:00Z");
    final Instant timestamp2 = Instant.parse("2026-06-25T10:00:01Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp1);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp2);

    // When-Then
    assertThat(event1).isNotEqualTo(event2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event1 =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);
    final ExecutionStatusEvent event2 =
        new ExecutionStatusEvent(
            "exec-999",
            "node-999",
            "workflow-999",
            "session-999",
            "FAILURE",
            "mcp",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
  }

  @Test
  void toString_containsFieldValues() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When
    final String actualToString = event.toString();

    // Then
    assertThat(actualToString).contains("exec-123");
    assertThat(actualToString).contains("node-456");
    assertThat(actualToString).contains("workflow-789");
    assertThat(actualToString).contains("session-012");
    assertThat(actualToString).contains("RUNNING");
    assertThat(actualToString).contains("core");
  }

  @Test
  void staticFactoryMethod_of_validInputs_returnsEventWithCurrentTimestamp() {
    // Given
    final String executionId = "exec-123";
    final String nodeId = "node-456";
    final String workflowId = "workflow-789";
    final String sessionId = "session-012";
    final String status = "RUNNING";
    final String module = "core";
    final Map<String, Object> metadata = Map.of("key", "value");
    final Throwable error = null;
    final Instant before = Instant.now();

    // When
    final ExecutionStatusEvent actualEvent =
        ExecutionStatusEvent.of(
            executionId, nodeId, workflowId, sessionId, status, module, metadata, error);
    final Instant after = Instant.now();

    // Then
    assertThat(actualEvent.executionId()).isEqualTo(executionId);
    assertThat(actualEvent.nodeId()).isEqualTo(nodeId);
    assertThat(actualEvent.workflowId()).isEqualTo(workflowId);
    assertThat(actualEvent.sessionId()).isEqualTo(sessionId);
    assertThat(actualEvent.status()).isEqualTo(status);
    assertThat(actualEvent.module()).isEqualTo(module);
    assertThat(actualEvent.metadata()).isEqualTo(metadata);
    assertThat(actualEvent.timestamp()).isNotNull();
    assertThat(actualEvent.timestamp()).isAfterOrEqualTo(before);
    assertThat(actualEvent.timestamp()).isBeforeOrEqualTo(after);
  }

  @Test
  void staticFactoryMethod_of_withNullMetadata_acceptsNull() {
    // Given
    final Instant before = Instant.now();

    // When
    final ExecutionStatusEvent actualEvent =
        ExecutionStatusEvent.of(
            "exec-123", "node-456", "workflow-789", "session-012", "RUNNING", "core", null, null);
    final Instant after = Instant.now();

    // Then
    assertThat(actualEvent.metadata()).isNull();
    assertThat(actualEvent.timestamp()).isNotNull();
    assertThat(actualEvent.timestamp()).isAfterOrEqualTo(before);
    assertThat(actualEvent.timestamp()).isBeforeOrEqualTo(after);
  }

  @Test
  void staticFactoryMethod_of_withNullError_acceptsNull() {
    // Given
    final Instant before = Instant.now();

    // When
    final ExecutionStatusEvent actualEvent =
        ExecutionStatusEvent.of(
            "exec-123", "node-456", "workflow-789", "session-012", "FAILURE", "core", null, null);
    final Instant after = Instant.now();

    // Then
    assertThat(actualEvent.error()).isNull();
    assertThat(actualEvent.timestamp()).isNotNull();
    assertThat(actualEvent.timestamp()).isAfterOrEqualTo(before);
    assertThat(actualEvent.timestamp()).isBeforeOrEqualTo(after);
  }

  @Test
  void staticFactoryMethod_of_timestampIsRecent() {
    // Given
    final Instant before = Instant.now();

    // When
    final ExecutionStatusEvent actualEvent =
        ExecutionStatusEvent.of(
            "exec-123", "node-456", "workflow-789", "session-012", "RUNNING", "core", null, null);
    final Instant after = Instant.now();

    // Then
    assertThat(actualEvent.timestamp()).isNotNull();
    assertThat(actualEvent.timestamp()).isAfterOrEqualTo(before.minusSeconds(1));
    assertThat(actualEvent.timestamp()).isBeforeOrEqualTo(after.plusSeconds(1));
  }

  @Test
  void staticFactoryMethod_of_sequentialCalls_haveIncreasingTimestamps()
      throws InterruptedException {
    // Given
    // When
    final ExecutionStatusEvent event1 =
        ExecutionStatusEvent.of(
            "exec-123", "node-456", "workflow-789", "session-012", "RUNNING", "core", null, null);
    Thread.sleep(10);
    final ExecutionStatusEvent event2 =
        ExecutionStatusEvent.of(
            "exec-123", "node-456", "workflow-789", "session-012", "SUCCESS", "core", null, null);

    // Then
    assertThat(event2.timestamp()).isAfter(event1.timestamp());
  }

  @Test
  void record_accessorsReturnCorrectTypes() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event.executionId()).isInstanceOf(String.class);
    assertThat(event.nodeId()).isInstanceOf(String.class);
    assertThat(event.workflowId()).isInstanceOf(String.class);
    assertThat(event.sessionId()).isInstanceOf(String.class);
    assertThat(event.status()).isInstanceOf(String.class);
    assertThat(event.module()).isInstanceOf(String.class);
    assertThat(event.timestamp()).isInstanceOf(Instant.class);
  }

  @Test
  void record_equals_withNull_returnsFalse() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event).isNotEqualTo(null);
  }

  @Test
  void record_equals_withDifferentType_returnsFalse() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    final ExecutionStatusEvent event =
        new ExecutionStatusEvent(
            "exec-123",
            "node-456",
            "workflow-789",
            "session-012",
            "RUNNING",
            "core",
            null,
            null,
            timestamp);

    // When-Then
    assertThat(event).isNotEqualTo("not an ExecutionStatusEvent");
  }
}
