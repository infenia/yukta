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
package com.infenia.yukta.logging.impl.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for InMemoryPluginLogWriter.
 *
 * <p>Verifies that the in-memory implementation correctly stores and retrieves log entries.
 */
@NoArgsConstructor
@DisplayName("InMemoryPluginLogWriter")
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.CommentDefaultAccessModifier",
  "PMD.AvoidDuplicateLiterals"
})
class InMemoryPluginLogWriterTest {

  /** Test execution ID. */
  private static final String EXECUTION_ID = "exec-123";

  /** Test session ID. */
  private static final String SESSION_ID = "session-456";

  /** The writer being tested. */
  private InMemoryPluginLogWriter writer;

  @BeforeEach
  void setUp() {
    final Map<String, List<PluginLogEntry>> storage = new ConcurrentHashMap<>();
    writer = new InMemoryPluginLogWriter(storage);
  }

  @Nested
  @DisplayName("Write Operations")
  @NoArgsConstructor
  class WriteOperationsTests {

    @Test
    void testWriteSingleEntry() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-id",
              "Plugin",
              LogStream.STDOUT,
              "Test message",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      writer.write(entry).block();

      final List<PluginLogEntry> stored = writer.getStorage().get(EXECUTION_ID);
      assertThat(stored).hasSize(1);
      assertThat(stored.getFirst()).isEqualTo(entry);
    }

    @Test
    void testWriteBatch() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-1",
              "Plugin 1",
              LogStream.STDOUT,
              "Message 1",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);
      final PluginLogEntry entry2 =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-2",
              "Plugin 2",
              LogStream.STDERR,
              "Message 2",
              LogLevel.ERROR,
              Instant.now(),
              null,
              null);

      writer.writeBatch(List.of(entry1, entry2)).block();

      final List<PluginLogEntry> stored = writer.getStorage().get(EXECUTION_ID);
      assertThat(stored).hasSize(2);
      assertThat(stored).containsExactly(entry1, entry2);
    }

    @Test
    @DisplayName("writeBatch with empty list returns empty without error")
    void writeBatch_emptyEntries_returnsEmpty() {
      writer.writeBatch(List.of()).block();
      assertThat(writer.getStorage()).isEmpty();
    }

    @Test
    @DisplayName("writeBatch groups entries by execution ID")
    void writeBatch_multipleExecutions_groupedByExecution() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              "exec-1",
              SESSION_ID,
              "plugin-id",
              "Plugin",
              LogStream.STDOUT,
              "Exec 1 msg",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);
      final PluginLogEntry entry2 =
          new PluginLogEntry(
              "exec-2",
              SESSION_ID,
              "plugin-id",
              "Plugin",
              LogStream.STDOUT,
              "Exec 2 msg",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      writer.writeBatch(List.of(entry1, entry2)).block();

      assertThat(writer.getStorage()).hasSize(2);
      assertThat(writer.getStorage().get("exec-1")).hasSize(1);
      assertThat(writer.getStorage().get("exec-2")).hasSize(1);
    }

    @Test
    @DisplayName("writeBatch appends to existing execution")
    void writeBatch_appendsToExistingExecution_doesNotReplace() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-1",
              "Plugin 1",
              LogStream.STDOUT,
              "Message 1",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);
      final PluginLogEntry entry2 =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-2",
              "Plugin 2",
              LogStream.STDOUT,
              "Message 2",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      writer.write(entry1).block();
      writer.writeBatch(List.of(entry2)).block();

      final List<PluginLogEntry> stored = writer.getStorage().get(EXECUTION_ID);
      assertThat(stored).hasSize(2);
    }
  }

  @Nested
  @DisplayName("Multiple Executions")
  @NoArgsConstructor
  class MultipleExecutionsTests {

    @Test
    void testMultipleExecutions() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              "exec-001",
              SESSION_ID,
              "plugin-id",
              "Plugin",
              LogStream.STDOUT,
              "Exec 1",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);
      final PluginLogEntry entry2 =
          new PluginLogEntry(
              "exec-002",
              SESSION_ID,
              "plugin-id",
              "Plugin",
              LogStream.STDOUT,
              "Exec 2",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      writer.write(entry1).block();
      writer.write(entry2).block();

      assertThat(writer.getStorage()).hasSize(2);
      assertThat(writer.getStorage().get("exec-001")).hasSize(1);
      assertThat(writer.getStorage().get("exec-002")).hasSize(1);
    }

    @Test
    @DisplayName("multiple writes to same execution append")
    void writeMultiple_sameExecution_appended() {
      final PluginLogEntry entry1 =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-1",
              "Plugin 1",
              LogStream.STDOUT,
              "First",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);
      final PluginLogEntry entry2 =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-2",
              "Plugin 2",
              LogStream.STDOUT,
              "Second",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      writer.write(entry1).block();
      writer.write(entry2).block();

      final List<PluginLogEntry> stored = writer.getStorage().get(EXECUTION_ID);
      assertThat(stored).hasSize(2);
    }
  }

  @Nested
  @DisplayName("Lifecycle Operations")
  @NoArgsConstructor
  class LifecycleTests {

    @Test
    @DisplayName("close does not throw exception")
    void close_returnsCompletedMono() {
      final var closeResult = writer.close();
      assertThat(closeResult).isNotNull();
      closeResult.block();
    }

    @Test
    @DisplayName("close returns Mono that completes")
    void close_completesWithoutError() {
      final PluginLogEntry entry =
          new PluginLogEntry(
              EXECUTION_ID,
              SESSION_ID,
              "plugin-id",
              "Plugin",
              LogStream.STDOUT,
              "Test message",
              LogLevel.INFO,
              Instant.now(),
              null,
              null);

      writer.write(entry).block();
      assertThat(writer.getStorage()).isNotEmpty();

      // Close should complete without error
      writer.close().block();
      // Note: close doesn't necessarily clear storage, just performs cleanup
      assertThat(writer).isNotNull();
    }
  }
}
