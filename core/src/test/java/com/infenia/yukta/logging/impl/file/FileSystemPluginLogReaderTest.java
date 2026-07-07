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
package com.infenia.yukta.logging.impl.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.logging.api.ExecutionSummary;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.PluginLogEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for FileSystemPluginLogReader. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.LinguisticNaming"})
class FileSystemPluginLogReaderTest {

  /** Temporary directory for test files. */
  @TempDir private Path tempDir;

  /** Log reader instance for testing. */
  private FileSystemPluginLogReader reader;

  /** Session ID used in test scenarios. */
  private static final String SESSION_ID = "session-456";

  @BeforeEach
  void setUp() {
    reader = new FileSystemPluginLogReader(tempDir.toString());
  }

  @Test
  void testReadExecution() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        """
        2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message 1
        2026-07-02T10:00:01 | STDERR | ERROR | plugin-2 | Plugin Two | Message 2
        """);

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(2);
    assertThat(entries.getFirst().message()).isEqualTo("Message 1");
    assertThat(entries.get(0).logLevel()).isEqualTo(LogLevel.INFO);
    assertThat(entries.get(0).pluginName()).isEqualTo("Plugin One");
    assertThat(entries.get(1).message()).isEqualTo("Message 2");
    assertThat(entries.get(1).logLevel()).isEqualTo(LogLevel.ERROR);
    assertThat(entries.get(1).pluginName()).isEqualTo("Plugin Two");
  }

  @Test
  void testReadSession() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-001.log"),
        "2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Exec 1\n");
    Files.writeString(
        sessionDir.resolve("exec-002.log"),
        "2026-07-02T10:00:01 | STDOUT | INFO | plugin-1 | Plugin One | Exec 2\n");

    final List<PluginLogEntry> entries = reader.readSession(SESSION_ID).collectList().block();

    assertThat(entries).hasSize(2);
  }

  @Test
  void testReadNonExistentExecution() {
    final List<PluginLogEntry> entries = reader.readExecution("non-existent").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void testGetRawContent() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        """
        2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message 1
        2026-07-02T10:00:01 | STDOUT | INFO | plugin-1 | Plugin One | Message 2
        """);

    final String content = reader.getRawContent("exec-123").block();

    assertThat(content).contains("Message 1");
    assertThat(content).contains("Message 2");
  }

  @Test
  void testListExecutions() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-001.log"),
        "2026-07-02T10:00:00+00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message 1\n");
    Files.writeString(
        sessionDir.resolve("exec-002.log"),
        "2026-07-02T10:00:05+00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message 2\n");

    final List<ExecutionSummary> summaries = reader.listExecutions(SESSION_ID).block();

    assertThat(summaries).hasSize(2);
    assertThat(summaries.getFirst().executionId()).isIn("exec-001", "exec-002");
  }

  @Test
  void testListExecutionsNonExistentSession() {
    final List<ExecutionSummary> summaries = reader.listExecutions("non-existent").block();

    assertThat(summaries).isEmpty();
  }

  @Test
  void testReadNonExistentSession() {
    final List<PluginLogEntry> entries = reader.readSession("non-existent").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void testReadExecutionNonExistentBaseDir() {
    final FileSystemPluginLogReader readerWithBadDir =
        new FileSystemPluginLogReader("/non/existent/path");
    final List<PluginLogEntry> entries =
        readerWithBadDir.readExecution("exec-123").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void testGetRawContentNonExistentExecution() {
    final String content = reader.getRawContent("non-existent").block();

    assertThat(content).isEmpty();
  }

  @Test
  void testReadExecutionWithMultipleDelimiters() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message with | delimiter\n");

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().message()).isEqualTo("Message with | delimiter");
  }

  @Test
  void testReadExecutionWithInvalidLogLine() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        """
        invalid line without delimiters
        2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Valid message
        """);

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().message()).isEqualTo("Valid message");
  }

  @Test
  void testListExecutionsSorted() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-001.log"),
        "2026-07-02T10:00:00+00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message 1\n");
    Files.writeString(
        sessionDir.resolve("exec-002.log"),
        "2026-07-02T10:00:10+00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message 2\n");

    final List<ExecutionSummary> summaries = reader.listExecutions(SESSION_ID).block();

    assertThat(summaries).hasSize(2);
    assertThat(summaries.get(0).executionId()).isEqualTo("exec-002");
    assertThat(summaries.get(1).executionId()).isEqualTo("exec-001");
  }

  @Test
  void testGetRawContentNonExistentBaseDir() {
    final FileSystemPluginLogReader readerWithBadDir =
        new FileSystemPluginLogReader("/non/existent/path");
    final String content = readerWithBadDir.getRawContent("exec-123").block();

    assertThat(content).isEmpty();
  }

  @Test
  void testReadExecutionEmptyLog() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(sessionDir.resolve("exec-empty.log"), "");

    final List<PluginLogEntry> entries = reader.readExecution("exec-empty").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void testReadSessionWithoutLogExtension() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.txt"),
        "2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message\n");

    final List<PluginLogEntry> entries = reader.readSession(SESSION_ID).collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void testReadExecutionStreamParsing() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "2026-07-02T10:00:00 | STDERR | ERROR | plugin-1 | Plugin One | Error message\n");

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(1);
  }

  @Test
  void readExecution_baseDirIsRegularFile_returnsEmptyList() throws IOException {
    final Path fileAsBaseDir = Files.createFile(tempDir.resolve("not-a-dir"));
    final FileSystemPluginLogReader readerWithFileBaseDir =
        new FileSystemPluginLogReader(fileAsBaseDir.toString());

    final List<PluginLogEntry> entries =
        readerWithFileBaseDir.readExecution("exec-123").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void readSession_sessionDirIsRegularFile_returnsEmptyList() throws IOException {
    Files.createFile(tempDir.resolve(SESSION_ID));

    final List<PluginLogEntry> entries = reader.readSession(SESSION_ID).collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void listExecutions_sessionDirIsRegularFile_returnsEmptyList() throws IOException {
    Files.createFile(tempDir.resolve(SESSION_ID));

    final List<ExecutionSummary> summaries = reader.listExecutions(SESSION_ID).block();

    assertThat(summaries).isEmpty();
  }

  @Test
  void getRawContent_baseDirIsRegularFile_returnsEmptyString() throws IOException {
    final Path fileAsBaseDir = Files.createFile(tempDir.resolve("not-a-dir"));
    final FileSystemPluginLogReader readerWithFileBaseDir =
        new FileSystemPluginLogReader(fileAsBaseDir.toString());

    final String content = readerWithFileBaseDir.getRawContent("exec-123").block();

    assertThat(content).isEmpty();
  }

  @Test
  void readExecution_escapedNewlineInMessage_unescapesToActualNewline() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Line one\\nLine two\n");

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().message()).isEqualTo("Line one\nLine two");
  }

  @Test
  void readExecution_escapedCarriageReturnInMessage_unescapesToActualCarriageReturn()
      throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Line one\\rLine two\n");

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().message()).isEqualTo("Line one\rLine two");
  }

  @Test
  void readExecution_logFileIsDirectory_returnsEmptyList() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.createDirectories(sessionDir.resolve("exec-123.log"));

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void readExecution_invalidLogStreamValue_skipsUnparseableLine() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "2026-07-02T10:00:00 | NOT_A_STREAM | INFO | plugin-1 | Plugin One | Message\n");

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void readExecution_unparseableTimestamp_fallsBackToCurrentInstant() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "not-a-timestamp | STDOUT | INFO | plugin-1 | Plugin One | Message\n");
    final Instant before = Instant.now();

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    final Instant after = Instant.now();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp()).isBetween(before, after);
  }

  @Test
  void readExecution_timestampWithoutOffset_parsesAtSystemDefaultZone() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-123.log"),
        "2026-07-02T10:00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message\n");

    final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().timestamp())
        .isEqualTo(
            java.time.LocalDateTime.parse("2026-07-02T10:00:00")
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant());
  }

  @Test
  void listExecutions_logFileWithNoParseableEntries_excludedFromSummaries() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(sessionDir.resolve("exec-invalid.log"), "not a valid log line\n");
    Files.writeString(
        sessionDir.resolve("exec-valid.log"),
        "2026-07-02T10:00:00+00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message\n");

    final List<ExecutionSummary> summaries = reader.listExecutions(SESSION_ID).block();

    assertThat(summaries).hasSize(1);
    assertThat(summaries.getFirst().executionId()).isEqualTo("exec-valid");
  }

  @Test
  void listExecutions_nonLogFilePresent_isIgnored() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.writeString(
        sessionDir.resolve("exec-001.log"),
        "2026-07-02T10:00:00+00:00 | STDOUT | INFO | plugin-1 | Plugin One | Message\n");
    Files.writeString(sessionDir.resolve("readme.txt"), "not a log file\n");

    final List<ExecutionSummary> summaries = reader.listExecutions(SESSION_ID).block();

    assertThat(summaries).hasSize(1);
    assertThat(summaries.getFirst().executionId()).isEqualTo("exec-001");
  }

  @Test
  void getRawContent_logFileMissingInExistingSessionDir_returnsEmptyString() throws IOException {
    Files.createDirectories(tempDir.resolve(SESSION_ID));

    final String content = reader.getRawContent("exec-123").block();

    assertThat(content).isEmpty();
  }

  @Test
  void readExecution_logFileMissingInExistingSessionDir_returnsEmptyList() throws IOException {
    Files.createDirectories(tempDir.resolve(SESSION_ID));

    final List<PluginLogEntry> entries =
        reader.readExecution("non-existent-exec").collectList().block();

    assertThat(entries).isEmpty();
  }

  @Test
  void getRawContent_logFileIsDirectory_returnsEmptyString() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    Files.createDirectories(sessionDir.resolve("exec-123.log"));

    final String content = reader.getRawContent("exec-123").block();

    assertThat(content).isEmpty();
  }

  @Test
  void readExecution_ioExceptionWhileScanningBaseDir_returnsEmptyList() throws IOException {
    Files.createDirectories(tempDir.resolve(SESSION_ID));
    final Set<PosixFilePermission> original = Files.getPosixFilePermissions(tempDir);
    try {
      Files.setPosixFilePermissions(tempDir, EnumSet.noneOf(PosixFilePermission.class));

      final List<PluginLogEntry> entries = reader.readExecution("exec-123").collectList().block();

      assertThat(entries).isEmpty();
    } finally {
      Files.setPosixFilePermissions(tempDir, original);
    }
  }

  @Test
  void readSession_ioExceptionWhileListingSessionDir_returnsEmptyList() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    final Set<PosixFilePermission> original = Files.getPosixFilePermissions(sessionDir);
    try {
      Files.setPosixFilePermissions(sessionDir, EnumSet.noneOf(PosixFilePermission.class));

      final List<PluginLogEntry> entries = reader.readSession(SESSION_ID).collectList().block();

      assertThat(entries).isEmpty();
    } finally {
      Files.setPosixFilePermissions(sessionDir, original);
    }
  }

  @Test
  void listExecutions_ioExceptionWhileListingSessionDir_returnsEmptyList() throws IOException {
    final Path sessionDir = Files.createDirectories(tempDir.resolve(SESSION_ID));
    final Set<PosixFilePermission> original = Files.getPosixFilePermissions(sessionDir);
    try {
      Files.setPosixFilePermissions(sessionDir, EnumSet.noneOf(PosixFilePermission.class));

      final List<ExecutionSummary> summaries = reader.listExecutions(SESSION_ID).block();

      assertThat(summaries).isEmpty();
    } finally {
      Files.setPosixFilePermissions(sessionDir, original);
    }
  }
}
