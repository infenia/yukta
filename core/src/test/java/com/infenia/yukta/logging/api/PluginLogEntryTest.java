// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.core.LogLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Test suite for {@link PluginLogEntry}. */
@NoArgsConstructor
@SuppressWarnings("unchecked")
class PluginLogEntryTest {

  /** Execution ID constant for test fixtures. */
  private static final String EXEC_ID = "exec-123";

  /** Session ID constant for test fixtures. */
  private static final String SESSION_ID = "session-456";

  /** Plugin ID constant for test fixtures. */
  private static final String PLUGIN_ID = "plugin-id";

  /** Plugin name constant for test fixtures. */
  private static final String PLUGIN_NAME = "Plugin Name";

  /** Message constant for test fixtures. */
  private static final String MESSAGE = "message";

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
            MESSAGE,
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
            MESSAGE,
            LogLevel.INFO,
            now,
            null,
            null);

    assertThat(entry1).isEqualTo(entry2);
    assertThat(entry1.hashCode()).isEqualTo(entry2.hashCode());
  }

  private static PluginLogEntry withMetadata(final Map<String, Object> metadata) {
    return new PluginLogEntry(
        EXEC_ID,
        SESSION_ID,
        PLUGIN_ID,
        PLUGIN_NAME,
        LogStream.CUSTOM,
        MESSAGE,
        LogLevel.INFO,
        Instant.EPOCH,
        "audit",
        metadata);
  }

  @Test
  void metadataIsFrozenDeeply() {
    final List<Object> inner = new ArrayList<>(List.of("a"));
    final Map<String, Object> nested = new ConcurrentHashMap<>(Map.of("k", "v"));
    final Set<Object> tags = new HashSet<>(Set.of("t"));
    final Map<String, Object> metadata = new ConcurrentHashMap<>();
    metadata.put("list", inner);
    metadata.put("map", nested);
    metadata.put("set", tags);
    metadata.put("plain", 1);

    final PluginLogEntry entry = withMetadata(metadata);
    inner.add("later");
    nested.put("later", "x");
    tags.add("later");

    assertThat(entry.metadata().get("list")).isEqualTo(List.of("a"));
    assertThat(entry.metadata().get("map")).isEqualTo(Map.of("k", "v"));
    assertThat(entry.metadata().get("set")).isEqualTo(Set.of("t"));
    assertThat(entry.metadata()).containsEntry("plain", 1);
    assertThatThrownBy(() -> ((List<Object>) entry.metadata().get("list")).add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void formatShowsTheCustomStreamNameAndTheMetadata() {
    final PluginLogEntry entry = withMetadata(new LinkedHashMap<>(Map.of("user", "ann")));

    assertThat(entry.format()).contains("audit: " + MESSAGE).endsWith("{user=ann}");
  }

  @Test
  void format_customStreamWithoutName_usesTheStreamName() {
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.CUSTOM,
            MESSAGE,
            LogLevel.INFO,
            Instant.EPOCH,
            null,
            null);

    assertThat(entry.format()).contains("CUSTOM: " + MESSAGE);
  }
}
