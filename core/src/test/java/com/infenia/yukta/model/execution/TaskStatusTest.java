// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link TaskStatus}. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class TaskStatusTest {

  @Test
  void isTerminal_successStatus_returnsTrue() {
    // When
    final boolean actualResult = TaskStatus.SUCCESS.isTerminal();

    // Then
    assertThat(actualResult).isTrue();
  }

  @Test
  void isTerminal_failureStatus_returnsTrue() {
    // When
    final boolean actualResult = TaskStatus.FAILURE.isTerminal();

    // Then
    assertThat(actualResult).isTrue();
  }

  @Test
  void isTerminal_errorStatus_returnsTrue() {
    // When
    final boolean actualResult = TaskStatus.ERROR.isTerminal();

    // Then
    assertThat(actualResult).isTrue();
  }

  @Test
  void isTerminal_stoppedStatus_returnsTrue() {
    assertThat(TaskStatus.STOPPED.isTerminal()).isTrue();
  }

  @Test
  void isTerminal_pendingStatus_returnsFalse() {
    // When
    final boolean actualResult = TaskStatus.PENDING.isTerminal();

    // Then
    assertThat(actualResult).isFalse();
  }

  @Test
  void isTerminal_runningStatus_returnsFalse() {
    // When
    final boolean actualResult = TaskStatus.RUNNING.isTerminal();

    // Then
    assertThat(actualResult).isFalse();
  }

  @Test
  void valueOf_successStatusString_returnsSuccessConstant() {
    // When
    final TaskStatus actualStatus = TaskStatus.valueOf("SUCCESS");

    // Then
    assertThat(actualStatus).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test
  void valueOf_failureStatusString_returnsFailureConstant() {
    // When
    final TaskStatus actualStatus = TaskStatus.valueOf("FAILURE");

    // Then
    assertThat(actualStatus).isEqualTo(TaskStatus.FAILURE);
  }

  @Test
  void valueOf_errorStatusString_returnsErrorConstant() {
    // When
    final TaskStatus actualStatus = TaskStatus.valueOf("ERROR");

    // Then
    assertThat(actualStatus).isEqualTo(TaskStatus.ERROR);
  }

  @Test
  void valueOf_pendingStatusString_returnsPendingConstant() {
    // When
    final TaskStatus actualStatus = TaskStatus.valueOf("PENDING");

    // Then
    assertThat(actualStatus).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void valueOf_runningStatusString_returnsRunningConstant() {
    // When
    final TaskStatus actualStatus = TaskStatus.valueOf("RUNNING");

    // Then
    assertThat(actualStatus).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void valueOf_invalidStatusString_throwsIllegalArgumentException() {
    // When-Then
    assertThatThrownBy(() -> TaskStatus.valueOf("INVALID_STATUS"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void values_called_returnsAllEnumConstants() {
    // When
    final TaskStatus[] actualValues = TaskStatus.values();

    // Then
    assertThat(actualValues)
        .containsExactly(
            TaskStatus.PENDING,
            TaskStatus.RUNNING,
            TaskStatus.SUCCESS,
            TaskStatus.FAILURE,
            TaskStatus.ERROR,
            TaskStatus.STOPPED,
            TaskStatus.CANCELLED,
            TaskStatus.TIMED_OUT,
            TaskStatus.SKIPPED,
            TaskStatus.UPSTREAM_FAILED,
            TaskStatus.ABORTED);
  }

  @Test
  void values_called_returnsArrayOfElevenElements() {
    // When
    final TaskStatus[] actualValues = TaskStatus.values();

    // Then
    assertThat(actualValues).hasSize(11);
  }

  @Test
  void isTerminal_newNodeEndStates_returnTrue() {
    assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(TaskStatus.TIMED_OUT.isTerminal()).isTrue();
    assertThat(TaskStatus.SKIPPED.isTerminal()).isTrue();
    assertThat(TaskStatus.UPSTREAM_FAILED.isTerminal()).isTrue();
    assertThat(TaskStatus.ABORTED.isTerminal()).isTrue();
  }

  @Test
  void isTerminalName_handlesTerminalNonTerminalAndUnknownNames() {
    assertThat(TaskStatus.isTerminalName("SUCCESS")).isTrue();
    assertThat(TaskStatus.isTerminalName("TIMED_OUT")).isTrue();
    assertThat(TaskStatus.isTerminalName("RUNNING")).isFalse();
    assertThat(TaskStatus.isTerminalName("PENDING")).isFalse();
    assertThat(TaskStatus.isTerminalName("NODE_PAUSED")).isFalse();
    assertThat(TaskStatus.isTerminalName(null)).isFalse();
  }
}
