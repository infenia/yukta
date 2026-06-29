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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for ControlBusStatus. */
@NoArgsConstructor
class ControlBusStatusTest {

  /** Session ID test constant. */
  private static final String SESSION_ID_123 = "session-123";

  /** Execution ID test constant. */
  private static final String EXEC_ID_456 = "exec-456";

  /** Completed status constant. */
  private static final String STATUS_COMPLETED = "COMPLETED";

  /** Gradle plugin name constant. */
  private static final String GRADLE = "gradle";

  /** Processor plugin type constant. */
  private static final String PROCESSOR = "PROCESSOR";

  /** Active status constant. */
  private static final String ACTIVE = "ACTIVE";

  /** Second execution ID test constant. */
  private static final String EXEC_ID_789 = "exec-789";

  /** Failed status constant. */
  private static final String STATUS_FAILED = "FAILED";

  /** Memory value constant 1024. */
  private static final String VALUE_1024 = "1024";

  /** Memory value constant 512. */
  private static final String VALUE_512 = "512";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final List<SessionExecutionInfo> activeSessions =
        List.of(new SessionExecutionInfo(SESSION_ID_123, 5, 10));
    final List<PluginRegistryEntry> pluginRegistry =
        List.of(new PluginRegistryEntry(GRADLE, PROCESSOR, ACTIVE));
    final SystemHealthMetrics systemHealth =
        new SystemHealthMetrics(75.5, 42, VALUE_1024, "2048", "2h");
    final List<ExecutionRecord> recentExecutions =
        List.of(new ExecutionRecord(SESSION_ID_123, EXEC_ID_456, STATUS_COMPLETED, "5000ms"));

    // When
    final ControlBusStatus status =
        new ControlBusStatus(activeSessions, pluginRegistry, systemHealth, recentExecutions);

    // Then
    assertThat(status.activeSessions()).hasSize(1);
    assertThat(status.pluginRegistry()).hasSize(1);
    assertThat(status.systemHealth()).isEqualTo(systemHealth);
    assertThat(status.recentExecutions()).hasSize(1);
  }

  @Test
  void constructor_withNullActiveSessions_convertsToEmptyList() {
    // Given-When
    final ControlBusStatus status =
        new ControlBusStatus(
            null,
            List.of(),
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            List.of());

    // Then
    assertThat(status.activeSessions()).isEmpty();
    assertThat(status.activeSessions()).isUnmodifiable();
  }

  @Test
  void constructor_withNullPluginRegistry_convertsToEmptyList() {
    // Given-When
    final ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            null,
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            List.of());

    // Then
    assertThat(status.pluginRegistry()).isEmpty();
    assertThat(status.pluginRegistry()).isUnmodifiable();
  }

  @Test
  void constructor_withNullRecentExecutions_convertsToEmptyList() {
    // Given-When
    final ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(),
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            null);

    // Then
    assertThat(status.recentExecutions()).isEmpty();
    assertThat(status.recentExecutions()).isUnmodifiable();
  }

  @Test
  void activeSessions_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final ControlBusStatus status =
        new ControlBusStatus(
            List.of(new SessionExecutionInfo(SESSION_ID_123, 5, 10)),
            List.of(),
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            List.of());

    // When-Then
    assertThatThrownBy(
            () -> status.activeSessions().add(new SessionExecutionInfo("session-456", 3, 8)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void pluginRegistry_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(new PluginRegistryEntry(GRADLE, PROCESSOR, ACTIVE)),
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            List.of());

    // When-Then
    assertThatThrownBy(
            () ->
                status
                    .pluginRegistry()
                    .add(new PluginRegistryEntry("maven", "TRIGGER", "INACTIVE")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void recentExecutions_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(),
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            List.of(new ExecutionRecord(SESSION_ID_123, EXEC_ID_456, STATUS_COMPLETED, "5000ms")));

    // When-Then
    assertThatThrownBy(
            () ->
                status
                    .recentExecutions()
                    .add(new ExecutionRecord("session-456", EXEC_ID_789, STATUS_FAILED, "3000ms")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final List<SessionExecutionInfo> activeSessions = List.of();
    final List<PluginRegistryEntry> pluginRegistry = List.of();
    final SystemHealthMetrics health =
        new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h");
    final List<ExecutionRecord> recentExecutions = List.of();
    final ControlBusStatus status1 =
        new ControlBusStatus(activeSessions, pluginRegistry, health, recentExecutions);
    final ControlBusStatus status2 =
        new ControlBusStatus(activeSessions, pluginRegistry, health, recentExecutions);

    // When-Then
    assertThat(status1).isEqualTo(status2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    final SystemHealthMetrics health1 =
        new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h");
    final SystemHealthMetrics health2 = new SystemHealthMetrics(60.0, 10, "512", "1024", "1h");
    final ControlBusStatus status1 = new ControlBusStatus(List.of(), List.of(), health1, List.of());
    final ControlBusStatus status2 = new ControlBusStatus(List.of(), List.of(), health2, List.of());

    // When-Then
    assertThat(status1).isNotEqualTo(status2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(),
            new SystemHealthMetrics(50.0, 10, VALUE_512, VALUE_1024, "1h"),
            List.of());

    // When
    final String actual = status.toString();

    // Then
    assertThat(actual).contains("ControlBusStatus");
  }
}
