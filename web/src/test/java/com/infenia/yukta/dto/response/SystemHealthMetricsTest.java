// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SystemHealthMetricsTest. */
@NoArgsConstructor
class SystemHealthMetricsTest {

  /** Memory 1024 MB constant. */
  private static final String MEMORY_1024 = "1024";

  /** Memory 2048 MB constant. */
  private static final String MEMORY_2048 = "2048";

  /** Uptime 2h 30m constant. */
  private static final String UPTIME_2H_30M = "2h 30m";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final double threadPoolUtilization = 75.5;
    final int queueDepth = 42;
    final String memoryUsedMb = MEMORY_1024;
    final String memoryMaxMb = MEMORY_2048;
    final String uptime = UPTIME_2H_30M;

    // When
    final SystemHealthMetrics metrics =
        new SystemHealthMetrics(
            threadPoolUtilization, queueDepth, memoryUsedMb, memoryMaxMb, uptime);

    // Then
    assertThat(metrics.threadPoolUtilization()).isEqualTo(threadPoolUtilization);
    assertThat(metrics.queueDepth()).isEqualTo(queueDepth);
    assertThat(metrics.memoryUsedMb()).isEqualTo(memoryUsedMb);
    assertThat(metrics.memoryMaxMb()).isEqualTo(memoryMaxMb);
    assertThat(metrics.uptime()).isEqualTo(uptime);
  }

  @Test
  void threadPoolUtilization_doubleValue_returnsCorrectValue() {
    // Given
    final double expectedUtilization = 85.3;
    final SystemHealthMetrics metrics =
        new SystemHealthMetrics(expectedUtilization, 10, "512", MEMORY_1024, "1h");

    // When
    final double actual = metrics.threadPoolUtilization();

    // Then
    assertThat(actual).isEqualTo(expectedUtilization);
  }

  @Test
  void queueDepth_intValue_returnsCorrectValue() {
    // Given
    final int expectedQueueDepth = 100;
    final SystemHealthMetrics metrics =
        new SystemHealthMetrics(50.0, expectedQueueDepth, "512", MEMORY_1024, "1h");

    // When
    final int actual = metrics.queueDepth();

    // Then
    assertThat(actual).isEqualTo(expectedQueueDepth);
  }

  @Test
  void memoryUsedMb_stringValue_returnsCorrectValue() {
    // Given
    final String expectedMemoryUsed = "2048";
    final SystemHealthMetrics metrics =
        new SystemHealthMetrics(50.0, 10, expectedMemoryUsed, "4096", "1h");

    // When
    final String actual = metrics.memoryUsedMb();

    // Then
    assertThat(actual).isEqualTo(expectedMemoryUsed);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final SystemHealthMetrics metrics1 =
        new SystemHealthMetrics(75.5, 42, MEMORY_1024, MEMORY_2048, UPTIME_2H_30M);
    final SystemHealthMetrics metrics2 =
        new SystemHealthMetrics(75.5, 42, MEMORY_1024, MEMORY_2048, UPTIME_2H_30M);

    // When-Then
    assertThat(metrics1).isEqualTo(metrics2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SystemHealthMetrics metrics1 =
        new SystemHealthMetrics(75.5, 42, MEMORY_1024, MEMORY_2048, UPTIME_2H_30M);
    final SystemHealthMetrics metrics2 =
        new SystemHealthMetrics(80.0, 42, MEMORY_1024, MEMORY_2048, UPTIME_2H_30M);

    // When-Then
    assertThat(metrics1).isNotEqualTo(metrics2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final SystemHealthMetrics metrics =
        new SystemHealthMetrics(75.5, 42, MEMORY_1024, MEMORY_2048, UPTIME_2H_30M);

    // When
    final String actual = metrics.toString();

    // Then
    assertThat(actual).contains("SystemHealthMetrics").contains("75.5");
  }
}
