// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link ControlError}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class ControlErrorTest {

  @Test
  void fullConstructor_allParametersProvided_createsInstanceWithAllFields() {
    // Given
    final String expectedNodeId = "node-123";
    final String expectedExecutionId = "exec-456";
    final String expectedReason = "validation failed";
    final String expectedDetail = "Invalid input format";
    final Instant expectedTimestamp = Instant.parse("2026-06-25T10:30:00Z");

    // When
    final ControlError error =
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
    final String expectedNodeId = "node-789";
    final String expectedExecutionId = "exec-012";
    final String expectedReason = "timeout occurred";
    final String expectedDetail = "Operation exceeded time limit";
    final Instant beforeConstruction = Instant.now();

    // When
    final ControlError error =
        new ControlError(expectedNodeId, expectedExecutionId, expectedReason, expectedDetail);
    final Instant afterConstruction = Instant.now();

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
    final String nodeId = "node-123";
    final String executionId = "exec-456";
    final String reason = "invalid state";
    final String detail = "Processing cannot continue";
    final Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");

    // When
    final ControlError error1 = new ControlError(nodeId, executionId, reason, detail, timestamp);
    final ControlError error2 = new ControlError(nodeId, executionId, reason, detail, timestamp);

    // Then
    assertThat(error1).isEqualTo(error2);
  }

  @Test
  void record_differentNodeId_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    final ControlError error1 = new ControlError("node-1", "exec-1", "reason", "detail", timestamp);
    final ControlError error2 = new ControlError("node-2", "exec-1", "reason", "detail", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentExecutionId_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    final ControlError error1 = new ControlError("node-1", "exec-1", "reason", "detail", timestamp);
    final ControlError error2 = new ControlError("node-1", "exec-2", "reason", "detail", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentReason_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    final ControlError error1 =
        new ControlError("node-1", "exec-1", "reason-1", "detail", timestamp);
    final ControlError error2 =
        new ControlError("node-1", "exec-1", "reason-2", "detail", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentDetail_areNotEqual() {
    // Given
    final Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");
    final ControlError error1 =
        new ControlError("node-1", "exec-1", "reason", "detail-1", timestamp);
    final ControlError error2 =
        new ControlError("node-1", "exec-1", "reason", "detail-2", timestamp);

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    // Given
    final ControlError error1 =
        new ControlError(
            "node-1", "exec-1", "reason", "detail", Instant.parse("2026-06-25T10:00:00Z"));
    final ControlError error2 =
        new ControlError(
            "node-1", "exec-1", "reason", "detail", Instant.parse("2026-06-25T11:00:00Z"));

    // Then
    assertThat(error1).isNotEqualTo(error2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    final String nodeId = "node-abc";
    final String executionId = "exec-xyz";
    final String reason = "error occurred";
    final String detail = "Exception details";
    final Instant timestamp = Instant.parse("2026-06-25T14:30:00Z");

    // When
    final ControlError error1 = new ControlError(nodeId, executionId, reason, detail, timestamp);
    final ControlError error2 = new ControlError(nodeId, executionId, reason, detail, timestamp);

    // Then
    assertThat(error1.hashCode()).isEqualTo(error2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    final ControlError error1 =
        new ControlError(
            "node-1", "exec-1", "reason-1", "detail-1", Instant.parse("2026-06-25T10:00:00Z"));
    final ControlError error2 =
        new ControlError(
            "node-2", "exec-2", "reason-2", "detail-2", Instant.parse("2026-06-25T11:00:00Z"));

    // Then
    assertThat(error1.hashCode()).isNotEqualTo(error2.hashCode());
  }

  @Test
  void record_accessor_nodeIdReturnsCorrectValue() {
    // Given
    final String expectedNodeId = "target-node-123";

    // When
    final ControlError error = new ControlError(expectedNodeId, "exec-1", "reason", "detail");

    // Then
    assertThat(error.nodeId()).isEqualTo(expectedNodeId);
  }

  @Test
  void record_accessor_executionIdReturnsCorrectValue() {
    // Given
    final String expectedExecutionId = "execution-xyz-789";

    // When
    final ControlError error = new ControlError("node-1", expectedExecutionId, "reason", "detail");

    // Then
    assertThat(error.executionId()).isEqualTo(expectedExecutionId);
  }

  @Test
  void record_accessor_reasonReturnsCorrectValue() {
    // Given
    final String expectedReason = "validation error";

    // When
    final ControlError error = new ControlError("node-1", "exec-1", expectedReason, "detail");

    // Then
    assertThat(error.reason()).isEqualTo(expectedReason);
  }

  @Test
  void record_accessor_detailReturnsCorrectValue() {
    // Given
    final String expectedDetail = "Missing required field 'name'";

    // When
    final ControlError error = new ControlError("node-1", "exec-1", "reason", expectedDetail);

    // Then
    assertThat(error.detail()).isEqualTo(expectedDetail);
  }

  @Test
  void record_accessor_timestampReturnsCorrectValue() {
    // Given
    final Instant expectedTimestamp = Instant.parse("2026-06-25T16:45:30Z");

    // When
    final ControlError error =
        new ControlError("node-1", "exec-1", "reason", "detail", expectedTimestamp);

    // Then
    assertThat(error.timestamp()).isEqualTo(expectedTimestamp);
  }

  @Test
  @SuppressWarnings("PMD.LinguisticNaming")
  void toString_containsAllFieldValues() {
    // Given
    final String nodeId = "node-123";
    final String executionId = "exec-456";
    final String reason = "critical error";
    final String detail = "System failure";
    final Instant timestamp = Instant.parse("2026-06-25T12:00:00Z");

    // When
    final ControlError error = new ControlError(nodeId, executionId, reason, detail, timestamp);
    final String toStringOutput = error.toString();

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
    final ControlError error = new ControlError("node-1", "exec-1", "reason", "detail");

    // Then
    assertThat(error).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    // Given
    final ControlError error = new ControlError("node-1", "exec-1", "reason", "detail");
    final String differentObject = "not a ControlError";

    // Then
    assertThat(error).isNotEqualTo(differentObject);
  }
}
