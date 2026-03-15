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
package com.infenia.yukta.model.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ControlBusStatusTest {

  @Test
  void testSessionExecutionInfoCreation() {
    SessionExecutionInfo info = new SessionExecutionInfo("session-123", 5, 10);
    assertEquals("session-123", info.sessionId());
    assertEquals(5, info.activeExecutions());
    assertEquals(10, info.totalWorkflows());
  }

  @Test
  void testPluginRegistryEntryCreation() {
    PluginRegistryEntry entry = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");
    assertEquals("gradle", entry.type());
    assertEquals("PROCESSOR", entry.category());
    assertEquals("ACTIVE", entry.status());
  }

  @Test
  void testSystemHealthMetricsCreation() {
    SystemHealthMetrics metrics = new SystemHealthMetrics(75.5, 42, "1024", "2048", "2h 30m");
    assertEquals(75.5, metrics.threadPoolUtilization());
    assertEquals(42, metrics.queueDepth());
    assertEquals("1024", metrics.memoryUsedMb());
    assertEquals("2048", metrics.memoryMaxMb());
    assertEquals("2h 30m", metrics.uptime());
  }

  @Test
  void testExecutionRecordCreation() {
    ExecutionRecord record = new ExecutionRecord("session-456", "exec-789", "COMPLETED", "5000ms");
    assertEquals("session-456", record.sessionId());
    assertEquals("exec-789", record.executionId());
    assertEquals("COMPLETED", record.status());
    assertEquals("5000ms", record.duration());
  }

  @Test
  void testControlBusStatusWithEmptyLists() {
    ControlBusStatus status =
        new ControlBusStatus(
            List.of(), List.of(), new SystemHealthMetrics(0.0, 0, "0", "0", "0s"), List.of());
    assertNotNull(status.activeSessions());
    assertNotNull(status.pluginRegistry());
    assertNotNull(status.systemHealth());
    assertNotNull(status.recentExecutions());
    assertTrue(status.activeSessions().isEmpty());
    assertTrue(status.pluginRegistry().isEmpty());
    assertTrue(status.recentExecutions().isEmpty());
  }

  @Test
  void testControlBusStatusWithPopulatedLists() {
    SessionExecutionInfo session1 = new SessionExecutionInfo("session-1", 2, 5);
    SessionExecutionInfo session2 = new SessionExecutionInfo("session-2", 3, 8);
    List<SessionExecutionInfo> activeSessions = List.of(session1, session2);

    PluginRegistryEntry plugin1 = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");
    PluginRegistryEntry plugin2 = new PluginRegistryEntry("docker", "TRIGGER", "ACTIVE");
    List<PluginRegistryEntry> pluginRegistry = List.of(plugin1, plugin2);

    SystemHealthMetrics healthMetrics = new SystemHealthMetrics(65.0, 25, "1536", "2048", "5h 15m");

    ExecutionRecord exec1 = new ExecutionRecord("session-1", "exec-001", "COMPLETED", "2500ms");
    ExecutionRecord exec2 = new ExecutionRecord("session-2", "exec-002", "RUNNING", "1200ms");
    List<ExecutionRecord> recentExecutions = List.of(exec1, exec2);

    ControlBusStatus status =
        new ControlBusStatus(activeSessions, pluginRegistry, healthMetrics, recentExecutions);

    assertEquals(2, status.activeSessions().size());
    assertEquals("session-1", status.activeSessions().get(0).sessionId());
    assertEquals(2, status.activeSessions().get(0).activeExecutions());

    assertEquals(2, status.pluginRegistry().size());
    assertEquals("gradle", status.pluginRegistry().get(0).type());
    assertEquals("PROCESSOR", status.pluginRegistry().get(0).category());

    assertEquals(65.0, status.systemHealth().threadPoolUtilization());
    assertEquals(25, status.systemHealth().queueDepth());
    assertEquals("1536", status.systemHealth().memoryUsedMb());

    assertEquals(2, status.recentExecutions().size());
    assertEquals("exec-001", status.recentExecutions().get(0).executionId());
    assertEquals("COMPLETED", status.recentExecutions().get(0).status());
  }

  @Test
  void testExecutionRecordWithVariousStatuses() {
    ExecutionRecord completed = new ExecutionRecord("s1", "e1", "COMPLETED", "3000ms");
    ExecutionRecord running = new ExecutionRecord("s2", "e2", "RUNNING", "1500ms");
    ExecutionRecord failed = new ExecutionRecord("s3", "e3", "FAILED", "4500ms");

    assertEquals("COMPLETED", completed.status());
    assertEquals("RUNNING", running.status());
    assertEquals("FAILED", failed.status());
  }

  @Test
  void testPluginRegistryEntryWithDifferentCategories() {
    PluginRegistryEntry trigger = new PluginRegistryEntry("api", "TRIGGER", "ACTIVE");
    PluginRegistryEntry processor = new PluginRegistryEntry("gradle", "PROCESSOR", "ACTIVE");
    PluginRegistryEntry terminal = new PluginRegistryEntry("logger", "TERMINAL", "INACTIVE");

    assertEquals("TRIGGER", trigger.category());
    assertEquals("PROCESSOR", processor.category());
    assertEquals("TERMINAL", terminal.category());
    assertEquals("INACTIVE", terminal.status());
  }

  @Test
  void testSystemHealthMetricsWithVariousValues() {
    SystemHealthMetrics low = new SystemHealthMetrics(10.0, 5, "256", "512", "30m");
    SystemHealthMetrics high = new SystemHealthMetrics(95.5, 150, "3072", "4096", "48h");

    assertEquals(10.0, low.threadPoolUtilization());
    assertEquals(5, low.queueDepth());
    assertEquals(95.5, high.threadPoolUtilization());
    assertEquals(150, high.queueDepth());
  }

  @Test
  void testSessionExecutionInfoWithZeroValues() {
    SessionExecutionInfo inactive = new SessionExecutionInfo("session-idle", 0, 0);
    assertEquals(0, inactive.activeExecutions());
    assertEquals(0, inactive.totalWorkflows());
  }
}
