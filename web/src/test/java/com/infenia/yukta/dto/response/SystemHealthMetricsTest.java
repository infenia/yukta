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
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemHealthMetricsTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    double threadPoolUtilization = 75.5;
    int queueDepth = 42;
    String memoryUsedMb = "1024";
    String memoryMaxMb = "2048";
    String uptime = "2h 30m";

    // When
    SystemHealthMetrics metrics =
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
    double expectedUtilization = 85.3;
    SystemHealthMetrics metrics =
        new SystemHealthMetrics(expectedUtilization, 10, "512", "1024", "1h");

    // When
    double actual = metrics.threadPoolUtilization();

    // Then
    assertThat(actual).isEqualTo(expectedUtilization);
  }

  @Test
  void queueDepth_intValue_returnsCorrectValue() {
    // Given
    int expectedQueueDepth = 100;
    SystemHealthMetrics metrics =
        new SystemHealthMetrics(50.0, expectedQueueDepth, "512", "1024", "1h");

    // When
    int actual = metrics.queueDepth();

    // Then
    assertThat(actual).isEqualTo(expectedQueueDepth);
  }

  @Test
  void memoryUsedMb_stringValue_returnsCorrectValue() {
    // Given
    String expectedMemoryUsed = "2048";
    SystemHealthMetrics metrics =
        new SystemHealthMetrics(50.0, 10, expectedMemoryUsed, "4096", "1h");

    // When
    String actual = metrics.memoryUsedMb();

    // Then
    assertThat(actual).isEqualTo(expectedMemoryUsed);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    SystemHealthMetrics metrics1 = new SystemHealthMetrics(75.5, 42, "1024", "2048", "2h 30m");
    SystemHealthMetrics metrics2 = new SystemHealthMetrics(75.5, 42, "1024", "2048", "2h 30m");

    // When-Then
    assertThat(metrics1).isEqualTo(metrics2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    SystemHealthMetrics metrics1 = new SystemHealthMetrics(75.5, 42, "1024", "2048", "2h 30m");
    SystemHealthMetrics metrics2 = new SystemHealthMetrics(80.0, 42, "1024", "2048", "2h 30m");

    // When-Then
    assertThat(metrics1).isNotEqualTo(metrics2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    SystemHealthMetrics metrics = new SystemHealthMetrics(75.5, 42, "1024", "2048", "2h 30m");

    // When
    String actual = metrics.toString();

    // Then
    assertThat(actual).contains("SystemHealthMetrics").contains("75.5");
  }
}
