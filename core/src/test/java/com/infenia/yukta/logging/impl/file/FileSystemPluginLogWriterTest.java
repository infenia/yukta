// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Tests for FileSystemPluginLogWriter. */
@NoArgsConstructor
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class FileSystemPluginLogWriterTest {

  /** Temporary directory for test files. */
  @TempDir private Path tempDir;

  /** Log writer instance for testing. */
  private FileSystemPluginLogWriter writer;

  /** Session ID used in test scenarios. */
  private static final String SESSION_ID = "session-456";

  /** Plugin ID used in test scenarios. */
  private static final String PLUGIN_ID = "plugin-id";

  @BeforeEach
  void setUp() {
    writer = new FileSystemPluginLogWriter(tempDir.toString());
  }

  @Test
  void testWriteSingleEntry() throws IOException {
    final PluginLogEntry entry =
        new PluginLogEntry(
            "exec-123",
            SESSION_ID,
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "Test message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    writer.write(entry).block();

    final Path logFile = tempDir.resolve(SESSION_ID).resolve("exec-123.log");
    assertThat(logFile).exists();
    final String content = Files.readString(logFile);
    assertThat(content).contains("Test message");
    assertThat(content).contains(PLUGIN_ID);
  }

  @Test
  void testWriteBatch() throws IOException {
    final PluginLogEntry entry1 =
        new PluginLogEntry(
            "exec-123",
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
            "exec-123",
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

    final Path logFile = tempDir.resolve(SESSION_ID).resolve("exec-123.log");
    assertThat(logFile).exists();
    final String content = Files.readString(logFile);
    assertThat(content).contains("Message 1");
    assertThat(content).contains("Message 2");
  }

  @Test
  void testClose() {
    final var result = writer.close();
    assertThat(result).isNotNull();
  }

  @Test
  void close_subscribed_completesAndLogsClosedMessage() {
    final var result = writer.close().block();
    assertThat(result).isNull();
  }

  @Test
  void testWithDefaultDir() {
    final var defaultWriter = FileSystemPluginLogWriter.withDefaultDir();
    assertThat(defaultWriter).isNotNull();
  }

  @Test
  void testEmptyBatch() {
    final var result = writer.writeBatch(List.of()).block();
    assertThat(result).isNull();
  }

  @Test
  void testMultipleExecutions() {
    final PluginLogEntry entry1 =
        new PluginLogEntry(
            "exec-001",
            SESSION_ID,
            PLUGIN_ID,
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
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "Exec 2",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    writer.write(entry1).block();
    writer.write(entry2).block();

    assertThat(tempDir.resolve(SESSION_ID).resolve("exec-001.log")).exists();
    assertThat(tempDir.resolve(SESSION_ID).resolve("exec-002.log")).exists();
  }

  @Test
  void writeToFile_existingLogFile_appendsContent() throws IOException {
    final PluginLogEntry firstEntry =
        new PluginLogEntry(
            "exec-123",
            SESSION_ID,
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "First message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);
    final PluginLogEntry secondEntry =
        new PluginLogEntry(
            "exec-123",
            SESSION_ID,
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "Second message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    writer.write(firstEntry).block();
    writer.write(secondEntry).block();

    final Path logFile = tempDir.resolve(SESSION_ID).resolve("exec-123.log");
    final String content = Files.readString(logFile);
    assertThat(content).contains("First message");
    assertThat(content).contains("Second message");
  }

  @Test
  void writeBatch_multipleExecutionIds_writesSeparateFiles() throws IOException {
    final PluginLogEntry entry1 =
        new PluginLogEntry(
            "exec-001",
            SESSION_ID,
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "Exec 1 message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);
    final PluginLogEntry entry2 =
        new PluginLogEntry(
            "exec-002",
            SESSION_ID,
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "Exec 2 message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    writer.writeBatch(List.of(entry1, entry2)).block();

    final Path logFile1 = tempDir.resolve(SESSION_ID).resolve("exec-001.log");
    final Path logFile2 = tempDir.resolve(SESSION_ID).resolve("exec-002.log");
    assertThat(logFile1).exists();
    assertThat(logFile2).exists();
    assertThat(Files.readString(logFile1)).contains("Exec 1 message").doesNotContain("Exec 2");
    assertThat(Files.readString(logFile2)).contains("Exec 2 message").doesNotContain("Exec 1");
  }

  @Test
  void write_thenRead_preservesLogLevelPluginNameAndEmbeddedNewline() {
    final PluginLogEntry entry =
        new PluginLogEntry(
            "exec-123",
            SESSION_ID,
            PLUGIN_ID,
            "Display Name",
            LogStream.STDOUT,
            "Line one\nLine two\rLine three",
            LogLevel.ERROR,
            Instant.now(),
            null,
            null);

    writer.write(entry).block();

    final FileSystemPluginLogReader reader = new FileSystemPluginLogReader(tempDir.toString());
    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries)
        .singleElement()
        .satisfies(
            readEntry -> {
              assertThat(readEntry.pluginId()).isEqualTo(PLUGIN_ID);
              assertThat(readEntry.pluginName()).isEqualTo("Display Name");
              assertThat(readEntry.logLevel()).isEqualTo(LogLevel.ERROR);
              assertThat(readEntry.message()).isEqualTo("Line one\nLine two\rLine three");
            });
  }

  @Test
  void writeToFile_ioExceptionOnCreateDirectories_logsErrorAndCompletes(final CapturedOutput output)
      throws IOException {
    final Path blockingFile = tempDir.resolve(SESSION_ID);
    Files.writeString(blockingFile, "not a directory");
    final FileSystemPluginLogWriter blockedWriter =
        new FileSystemPluginLogWriter(tempDir.toString());
    final PluginLogEntry entry =
        new PluginLogEntry(
            "exec-123",
            SESSION_ID,
            PLUGIN_ID,
            "Plugin",
            LogStream.STDOUT,
            "Test message",
            LogLevel.INFO,
            Instant.now(),
            null,
            null);

    final var result = blockedWriter.write(entry).block();

    assertThat(result).isNull();
    assertThat(output.getErr()).contains("Failed to write plugin logs to file");
  }
}
