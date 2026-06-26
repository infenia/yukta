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
package com.infenia.yukta.model.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ControlErrorTest {

  @Test
  void fullConstructor_allParametersProvided_createsInstanceWithAllFields() {
    // Given
    String expectedNodeId = "node-123";
    String expectedExecutionId = "exec-456";
    String expectedReason = "validation failed";
    String expectedDetail = "Invalid input format";
    Instant expectedTimestamp = Instant.parse("2026-06-25T10:30:00Z");

    // When
    ControlError error =
        new ControlError(
            expectedNodeId, expectedExecutionId, expectedReason, expectedDetail, expectedTimestamp);

    // Then
    assertThat(error.nodeId()).isEqualTo(expectedNodeId);
    assertThat(error.executionId()).isEqualTo(expectedExecutionId);
    assertThat(error.reason()).isEqualTo(expectedReason);
    assertThat(error.detail()).isEqualTo(expectedDetail);
    assertThat(error.timestamp()).isEqualTo(expectedTimestamp);
  }

  @Test
  void convenienceConstructor_withoutTimestamp_setsTimestampToNow() {
    // Given
    String expectedNodeId = "node-789";
    String expectedExecutionId = "exec-012";
    String expectedReason = "timeout occurred";
    String expectedDetail = "Operation exceeded time limit";
    Instant beforeConstruction = Instant.now();

    // When
    ControlError error =
        new ControlError(expectedNodeId, expectedExecutionId, expectedReason, expectedDetail);
    Instant afterConstruction = Instant.now();

    // Then
    assertThat(error.nodeId()).isEqualTo(expectedNodeId);
    assertThat(error.executionId()).isEqualTo(expectedExecutionId);
    assertThat(error.reason()).isEqualTo(expectedReason);
    assertThat(error.detail()).isEqualTo(expectedDetail);
    // NOTE: ControlError convenience constructor uses Instant.now() directly; tolerant assertion
    // used
    assertThat(error.timestamp()).isBetween(beforeConstruction, afterConstruction);
  }

  @Test
  void record_sameFieldValues_areEqual() {
    // Given
    String nodeId = "node-123";
    String executionId = "exec-456";
    String reason = "invalid state";
    String detail = "Processing cannot continue";
    Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");

    // When
    ControlError error1 = new ControlError(nodeId, executionId, reason, detail, timestamp);
    ControlError error2 = new ControlError(nodeId, executionId, reason, detail, timestamp);

    // Then
    assertThat(error1).isEqualTo(error2);
  }

  @Test
  void record_differentNodeId_areNotEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    ControlError error1 = new ControlError("node-1", "exec-1", "reason", "detail", timestamp);
    ControlError error2 = new ControlError("node-2", "exec-1", "reason", "detail", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentExecutionId_areNotEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    ControlError error1 = new ControlError("node-1", "exec-1", "reason", "detail", timestamp);
    ControlError error2 = new ControlError("node-1", "exec-2", "reason", "detail", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentReason_areNotEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    ControlError error1 = new ControlError("node-1", "exec-1", "reason-1", "detail", timestamp);
    ControlError error2 = new ControlError("node-1", "exec-1", "reason-2", "detail", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentDetail_areNotEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    ControlError error1 = new ControlError("node-1", "exec-1", "reason", "detail-1", timestamp);
    ControlError error2 = new ControlError("node-1", "exec-1", "reason", "detail-2", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    // Given
    ControlError error1 =
        new ControlError(
            "node-1", "exec-1", "reason", "detail", Instant.parse("2026-06-25T10:00:00Z"));
    ControlError error2 =
        new ControlError(
            "node-1", "exec-1", "reason", "detail", Instant.parse("2026-06-25T11:00:00Z"));

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    String nodeId = "node-abc";
    String executionId = "exec-xyz";
    String reason = "error occurred";
    String detail = "Exception details";
    Instant timestamp = Instant.parse("2026-06-25T14:30:00Z");

    // When
    ControlError error1 = new ControlError(nodeId, executionId, reason, detail, timestamp);
    ControlError error2 = new ControlError(nodeId, executionId, reason, detail, timestamp);

    // Then
    assertThat(error1.hashCode()).isEqualTo(error2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    ControlError error1 =
        new ControlError(
            "node-1", "exec-1", "reason-1", "detail-1", Instant.parse("2026-06-25T10:00:00Z"));
    ControlError error2 =
        new ControlError(
            "node-2", "exec-2", "reason-2", "detail-2", Instant.parse("2026-06-25T11:00:00Z"));

    // Then
    assertThat(error1.hashCode()).isNotEqualTo(error2.hashCode());
  }

  @Test
  void record_accessor_nodeIdReturnsCorrectValue() {
    // Given
    String expectedNodeId = "target-node-123";

    // When
    ControlError error = new ControlError(expectedNodeId, "exec-1", "reason", "detail");

    // Then
    assertThat(error.nodeId()).isEqualTo(expectedNodeId);
  }

  @Test
  void record_accessor_executionIdReturnsCorrectValue() {
    // Given
    String expectedExecutionId = "execution-xyz-789";

    // When
    ControlError error = new ControlError("node-1", expectedExecutionId, "reason", "detail");

    // Then
    assertThat(error.executionId()).isEqualTo(expectedExecutionId);
  }

  @Test
  void record_accessor_reasonReturnsCorrectValue() {
    // Given
    String expectedReason = "validation error";

    // When
    ControlError error = new ControlError("node-1", "exec-1", expectedReason, "detail");

    // Then
    assertThat(error.reason()).isEqualTo(expectedReason);
  }

  @Test
  void record_accessor_detailReturnsCorrectValue() {
    // Given
    String expectedDetail = "Missing required field 'name'";

    // When
    ControlError error = new ControlError("node-1", "exec-1", "reason", expectedDetail);

    // Then
    assertThat(error.detail()).isEqualTo(expectedDetail);
  }

  @Test
  void record_accessor_timestampReturnsCorrectValue() {
    // Given
    Instant expectedTimestamp = Instant.parse("2026-06-25T16:45:30Z");

    // When
    ControlError error =
        new ControlError("node-1", "exec-1", "reason", "detail", expectedTimestamp);

    // Then
    assertThat(error.timestamp()).isEqualTo(expectedTimestamp);
  }

  @Test
  void toString_containsAllFieldValues() {
    // Given
    String nodeId = "node-123";
    String executionId = "exec-456";
    String reason = "critical error";
    String detail = "System failure";
    Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");

    // When
    ControlError error = new ControlError(nodeId, executionId, reason, detail, timestamp);
    String toStringOutput = error.toString();

    // Then
    assertThat(toStringOutput)
        .contains("node-123")
        .contains("exec-456")
        .contains("critical error")
        .contains("System failure");
  }

  @Test
  void record_nullComparison_isNotEqual() {
    // Given
    ControlError error = new ControlError("node-1", "exec-1", "reason", "detail");

    // Then
    assertThat(error).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    // Given
    ControlError error = new ControlError("node-1", "exec-1", "reason", "detail");
    String differentObject = "not a ControlError";

    // Then
    assertThat(error).isNotEqualTo(differentObject);
  }
}
