// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Test suite for {@link PluginLogEntry}. */
@NoArgsConstructor
class PluginLogEntryTest {

  /** Execution ID constant for test fixtures. */
  private static final String EXEC_ID = "exec-123";

  /** Session ID constant for test fixtures. */
  private static final String SESSION_ID = "session-456";

  /** Plugin ID constant for test fixtures. */
  private static final String PLUGIN_ID = "plugin-id";

  /** Plugin name constant for test fixtures. */
  private static final String PLUGIN_NAME = "Plugin Name";

  @Test
  void testCreatePluginLogEntry() {
    final Instant now = Instant.now();

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            "process-executor",
            "Process Executor",
            LogStream.STDOUT,
            "Hello World",
            LogLevel.INFO,
            now,
            null,
            null);

    assertThat(entry.executionId()).isEqualTo(EXEC_ID);
    assertThat(entry.sessionId()).isEqualTo(SESSION_ID);
    assertThat(entry.pluginId()).isEqualTo("process-executor");
    assertThat(entry.pluginName()).isEqualTo("Process Executor");
    assertThat(entry.stream()).isEqualTo(LogStream.STDOUT);
    assertThat(entry.message()).isEqualTo("Hello World");
    assertThat(entry.logLevel()).isEqualTo(LogLevel.INFO);
    assertThat(entry.timestamp()).isEqualTo(now);
  }

  @Test
  void testFormat() {
    final Instant now = Instant.ofEpochMilli(1_000_000_000_000L);

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDERR,
            "Error message",
            LogLevel.ERROR,
            now,
            null,
            null);

    final String formatted = entry.format();
    assertThat(formatted).contains("ERROR");
    assertThat(formatted).contains("plugin-id");
    assertThat(formatted).contains("Plugin Name");
    assertThat(formatted).contains("Error message");
    assertThat(formatted).contains("STDERR");
  }

  @Test
  void testDifferentLogLevels() {
    final Instant now = Instant.now();

    final PluginLogEntry debugEntry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            "Debug message",
            LogLevel.DEBUG,
            now,
            null,
            null);

    final PluginLogEntry warnEntry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            "Warning message",
            LogLevel.WARN,
            now,
            null,
            null);

    assertThat(debugEntry.logLevel()).isEqualTo(LogLevel.DEBUG);
    assertThat(warnEntry.logLevel()).isEqualTo(LogLevel.WARN);
  }

  @Test
  void testAllFieldsAccessible() {
    final Instant now = Instant.now();
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.CUSTOM,
            "Custom message",
            LogLevel.WARN,
            now,
            null,
            null);

    assertThat(entry.executionId()).isEqualTo(EXEC_ID);
    assertThat(entry.sessionId()).isEqualTo(SESSION_ID);
    assertThat(entry.pluginId()).isEqualTo(PLUGIN_ID);
    assertThat(entry.pluginName()).isEqualTo(PLUGIN_NAME);
    assertThat(entry.stream()).isEqualTo(LogStream.CUSTOM);
    assertThat(entry.message()).isEqualTo("Custom message");
    assertThat(entry.logLevel()).isEqualTo(LogLevel.WARN);
    assertThat(entry.timestamp()).isEqualTo(now);
  }

  @Test
  void testEqualsAndHashCode() {
    final Instant now = Instant.ofEpochMilli(1_000_000_000_000L);
    final PluginLogEntry entry1 =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            "message",
            LogLevel.INFO,
            now,
            null,
            null);
    final PluginLogEntry entry2 =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            "message",
            LogLevel.INFO,
            now,
            null,
            null);

    assertThat(entry1).isEqualTo(entry2);
    assertThat(entry1.hashCode()).isEqualTo(entry2.hashCode());
  }
}
