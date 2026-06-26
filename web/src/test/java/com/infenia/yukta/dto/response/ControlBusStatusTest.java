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
import org.junit.jupiter.api.Test;

class ControlBusStatusTest {

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    List<SessionExecutionInfo> activeSessions =
        List.of(new SessionExecutionInfo("session-123", 5, 10));
    List<PluginRegistryEntry> pluginRegistry =
        List.of(new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE"));
    SystemHealthMetrics systemHealth = new SystemHealthMetrics(75.5, 42, "1024", "2048", "2h");
    List<ExecutionRecord> recentExecutions =
        List.of(new ExecutionRecord("session-123", "exec-456", "COMPLETED", "5000ms"));

    // When
    ControlBusStatus status =
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
    ControlBusStatus status =
        new ControlBusStatus(
            null, List.of(), new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"), List.of());

    // Then
    assertThat(status.activeSessions()).isEmpty();
    assertThat(status.activeSessions()).isUnmodifiable();
  }

  @Test
  void constructor_withNullPluginRegistry_convertsToEmptyList() {
    // Given-When
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(), null, new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"), List.of());

    // Then
    assertThat(status.pluginRegistry()).isEmpty();
    assertThat(status.pluginRegistry()).isUnmodifiable();
  }

  @Test
  void constructor_withNullRecentExecutions_convertsToEmptyList() {
    // Given-When
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(), List.of(), new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"), null);

    // Then
    assertThat(status.recentExecutions()).isEmpty();
    assertThat(status.recentExecutions()).isUnmodifiable();
  }

  @Test
  void activeSessions_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(new SessionExecutionInfo("session-123", 5, 10)),
            List.of(),
            new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"),
            List.of());

    // When-Then
    assertThatThrownBy(
            () -> status.activeSessions().add(new SessionExecutionInfo("session-456", 3, 8)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void pluginRegistry_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE")),
            new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"),
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
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(),
            new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"),
            List.of(new ExecutionRecord("session-123", "exec-456", "COMPLETED", "5000ms")));

    // When-Then
    assertThatThrownBy(
            () ->
                status
                    .recentExecutions()
                    .add(new ExecutionRecord("session-456", "exec-789", "FAILED", "3000ms")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    List<SessionExecutionInfo> activeSessions = List.of();
    List<PluginRegistryEntry> pluginRegistry = List.of();
    SystemHealthMetrics health = new SystemHealthMetrics(50.0, 10, "512", "1024", "1h");
    List<ExecutionRecord> recentExecutions = List.of();
    ControlBusStatus status1 =
        new ControlBusStatus(activeSessions, pluginRegistry, health, recentExecutions);
    ControlBusStatus status2 =
        new ControlBusStatus(activeSessions, pluginRegistry, health, recentExecutions);

    // When-Then
    assertThat(status1).isEqualTo(status2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    SystemHealthMetrics health1 = new SystemHealthMetrics(50.0, 10, "512", "1024", "1h");
    SystemHealthMetrics health2 = new SystemHealthMetrics(60.0, 10, "512", "1024", "1h");
    ControlBusStatus status1 = new ControlBusStatus(List.of(), List.of(), health1, List.of());
    ControlBusStatus status2 = new ControlBusStatus(List.of(), List.of(), health2, List.of());

    // When-Then
    assertThat(status1).isNotEqualTo(status2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(),
            List.of(),
            new SystemHealthMetrics(50.0, 10, "512", "1024", "1h"),
            List.of());

    // When
    String actual = status.toString();

    // Then
    assertThat(actual).contains("ControlBusStatus");
  }
}
