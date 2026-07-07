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

import com.infenia.yukta.logging.api.ExecutionSummary;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for InMemoryPluginLogReader.
 *
 * <p>Verifies that the in-memory implementation correctly reads and filters log entries with
 * complete branch coverage.
 */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LinguisticNaming"
})
class InMemoryPluginLogReaderTest {

  private InMemoryPluginLogReader reader;
  private Map<String, List<PluginLogEntry>> storage;

  private static final String EXECUTION_ID_001 = "exec-001";
  private static final String EXECUTION_ID_002 = "exec-002";
  private static final String EXECUTION_ID_003 = "exec-003";
  private static final String EXECUTION_ID_004 = "exec-004";

  private static final String SESSION_ID_456 = "session-456";
  private static final String SESSION_ID_789 = "session-789";

  private static final String PLUGIN_ID_1 = "plugin-1";
  private static final String PLUGIN_ID_2 = "plugin-2";

  private static final String PLUGIN_NAME_1 = "Plugin 1";
  private static final String PLUGIN_NAME_2 = "Plugin 2";

  @BeforeEach
  void setUp() {
    storage = new ConcurrentHashMap<>();
    reader = new InMemoryPluginLogReader(storage);
  }

  private void addEntry(
      final String executionId,
      final String sessionId,
      final String pluginId,
      final String pluginName,
      final String message,
      final Instant timestamp) {
    final PluginLogEntry entry =
        new PluginLogEntry(
            executionId,
            sessionId,
            pluginId,
            pluginName,
            LogStream.STDOUT,
            message,
            LogLevel.INFO,
            timestamp,
            null,
            null);
    storage.computeIfAbsent(executionId, _ -> new ArrayList<>()).add(entry);
  }

  private void addEntry(
      final String executionId,
      final String sessionId,
      final String pluginId,
      final String pluginName,
      final String message) {
    addEntry(executionId, sessionId, pluginId, pluginName, message, Instant.now());
  }

  // ===== readExecution Tests =====

  @Test
  void readExecution_executionIdExists_returnsAllEntries() {
    // Given
    final Instant now = Instant.now();
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Message 1", now);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_2, PLUGIN_NAME_2, "Message 2", now);

    // When
    final List<PluginLogEntry> actualEntries =
        reader.readExecution(EXECUTION_ID_001).collectList().block();

    // Then
    assertThat(actualEntries).hasSize(2);
    assertThat(actualEntries)
        .extracting(PluginLogEntry::message)
        .containsExactly("Message 1", "Message 2");
    assertThat(actualEntries)
        .extracting(PluginLogEntry::executionId)
        .containsOnly(EXECUTION_ID_001);
  }

  @Test
  void readExecution_executionIdNotExists_returnsEmptyFlux() {
    // Given: storage is empty
    // When
    final List<PluginLogEntry> actualEntries =
        reader.readExecution("non-existent-execution").collectList().block();

    // Then
    assertThat(actualEntries).isEmpty();
  }

  @Test
  void readExecution_multipleEntriesDifferentPlugins_preservesOrder() {
    // Given
    final Instant now = Instant.now();
    final Instant oneSecondLater = now.plusSeconds(1);
    final Instant twoSecondsLater = now.plusSeconds(2);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "First", now);
    addEntry(
        EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_2, PLUGIN_NAME_2, "Second", oneSecondLater);
    addEntry(
        EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Third", twoSecondsLater);

    // When
    final List<PluginLogEntry> actualEntries =
        reader.readExecution(EXECUTION_ID_001).collectList().block();

    // Then - entries should be in insertion order
    assertThat(actualEntries)
        .extracting(PluginLogEntry::message)
        .containsExactly("First", "Second", "Third");
  }

  // ===== readSession Tests =====

  @Test
  void readSession_entriesMatchingSessionId_returnsFiltered() {
    // Given
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Exec 1");
    addEntry(EXECUTION_ID_002, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Exec 2");
    addEntry(EXECUTION_ID_003, SESSION_ID_789, PLUGIN_ID_1, PLUGIN_NAME_1, "Other session");

    // When
    final List<PluginLogEntry> actualEntries =
        reader.readSession(SESSION_ID_456).collectList().block();

    // Then
    assertThat(actualEntries).hasSize(2);
    assertThat(actualEntries)
        .extracting(PluginLogEntry::executionId)
        .containsExactlyInAnyOrder(EXECUTION_ID_001, EXECUTION_ID_002);
    assertThat(actualEntries).extracting(PluginLogEntry::sessionId).containsOnly(SESSION_ID_456);
  }

  @Test
  void readSession_noEntriesMatchSessionId_returnsEmptyFlux() {
    // Given
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Exec 1");
    addEntry(EXECUTION_ID_002, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Exec 2");

    // When
    final List<PluginLogEntry> actualEntries =
        reader.readSession("non-existent-session").collectList().block();

    // Then - filter predicate fails, no entries returned
    assertThat(actualEntries).isEmpty();
  }

  @Test
  void readSession_storageEmpty_returnsEmptyFlux() {
    // Given: storage is completely empty
    // When
    final List<PluginLogEntry> actualEntries =
        reader.readSession(SESSION_ID_456).collectList().block();

    // Then
    assertThat(actualEntries).isEmpty();
  }

  @Test
  void readSession_multipleExecutionsDifferentSessions_filtersCorrectly() {
    // Given
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Session 456 - Exec 1");
    addEntry(EXECUTION_ID_002, SESSION_ID_789, PLUGIN_ID_1, PLUGIN_NAME_1, "Session 789 - Exec 1");
    addEntry(EXECUTION_ID_003, SESSION_ID_456, PLUGIN_ID_2, PLUGIN_NAME_2, "Session 456 - Exec 2");
    addEntry(EXECUTION_ID_004, SESSION_ID_789, PLUGIN_ID_2, PLUGIN_NAME_2, "Session 789 - Exec 2");

    // When
    final List<PluginLogEntry> actualEntries =
        reader.readSession(SESSION_ID_789).collectList().block();

    // Then
    assertThat(actualEntries).hasSize(2);
    assertThat(actualEntries)
        .extracting(PluginLogEntry::executionId)
        .containsExactlyInAnyOrder(EXECUTION_ID_002, EXECUTION_ID_004);
    assertThat(actualEntries).extracting(PluginLogEntry::sessionId).containsOnly(SESSION_ID_789);
  }

  // ===== listExecutions Tests =====

  @Test
  void listExecutions_multipleSummaries_sortedDescendingByStartTime() {
    // Given - create three executions with different timestamps
    final Instant earlier = Instant.parse("2026-01-01T10:00:00Z");
    final Instant middle = Instant.parse("2026-01-01T12:00:00Z");
    final Instant later = Instant.parse("2026-01-01T14:00:00Z");

    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "First entry", earlier);
    addEntry(EXECUTION_ID_002, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Second entry", middle);
    addEntry(EXECUTION_ID_003, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Third entry", later);

    // When
    final List<ExecutionSummary> actualSummaries = reader.listExecutions(SESSION_ID_456).block();

    // Then - sorted descending (most recent/latest startTime first)
    assertThat(actualSummaries).hasSize(3);
    assertThat(actualSummaries)
        .extracting(ExecutionSummary::executionId)
        .containsExactly(EXECUTION_ID_003, EXECUTION_ID_002, EXECUTION_ID_001);
  }

  @Test
  void listExecutions_noMatchingSession_returnsEmptyList() {
    // Given
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Entry 1");
    addEntry(EXECUTION_ID_002, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Entry 2");

    // When
    final List<ExecutionSummary> actualSummaries =
        reader.listExecutions("non-existent-session").block();

    // Then
    assertThat(actualSummaries).isEmpty();
  }

  @Test
  void listExecutions_emptyExecutionEntry_isFiltered() {
    // Given
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Entry 1");
    // Add empty list for EXECUTION_ID_002 (simulates an empty execution)
    storage.put(EXECUTION_ID_002, new ArrayList<>());
    addEntry(EXECUTION_ID_003, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Entry 3");

    // When
    final List<ExecutionSummary> actualSummaries = reader.listExecutions(SESSION_ID_456).block();

    // Then - empty execution is filtered out
    assertThat(actualSummaries).hasSize(2);
    assertThat(actualSummaries)
        .extracting(ExecutionSummary::executionId)
        .doesNotContain(EXECUTION_ID_002)
        .containsExactlyInAnyOrder(EXECUTION_ID_001, EXECUTION_ID_003);
  }

  @Test
  void listExecutions_twoExecutionsSameSession_correctEntryCount() {
    // Given
    final Instant earlier = Instant.parse("2026-01-01T10:00:00Z");
    final Instant later = Instant.parse("2026-01-01T11:00:00Z");

    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Line 1", earlier);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Line 2", earlier);
    addEntry(EXECUTION_ID_002, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Line 3", later);

    // When
    final List<ExecutionSummary> actualSummaries = reader.listExecutions(SESSION_ID_456).block();

    // Then
    assertThat(actualSummaries).hasSize(2);
    assertThat(actualSummaries)
        .filteredOn(summary -> EXECUTION_ID_001.equals(summary.executionId()))
        .extracting(ExecutionSummary::entryCount)
        .containsExactly(2L);
    assertThat(actualSummaries)
        .filteredOn(summary -> EXECUTION_ID_002.equals(summary.executionId()))
        .extracting(ExecutionSummary::entryCount)
        .containsExactly(1L);
  }

  @Test
  void listExecutions_summarySessionIdMatches() {
    // Given
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Entry");

    // When
    final List<ExecutionSummary> actualSummaries = reader.listExecutions(SESSION_ID_456).block();

    // Then
    assertThat(actualSummaries).hasSize(1);
    assertThat(actualSummaries.getFirst().sessionId()).isEqualTo(SESSION_ID_456);
  }

  @Test
  void listExecutions_startTimeIsFirstEntryTimestamp() {
    // Given
    final Instant firstTime = Instant.parse("2026-01-01T10:00:00Z");
    final Instant secondTime = Instant.parse("2026-01-01T10:05:00Z");
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "First", firstTime);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Second", secondTime);

    // When
    final List<ExecutionSummary> actualSummaries = reader.listExecutions(SESSION_ID_456).block();

    // Then - startTime should be first entry's timestamp
    final LocalDateTime expectedStartTime =
        ZonedDateTime.ofInstant(firstTime, ZoneId.systemDefault()).toLocalDateTime();
    assertThat(actualSummaries).hasSize(1);
    assertThat(actualSummaries.getFirst().startTime()).isEqualTo(expectedStartTime);
  }

  @Test
  void listExecutions_endTimeIsLastEntryTimestamp() {
    // Given
    final Instant firstTime = Instant.parse("2026-01-01T10:00:00Z");
    final Instant secondTime = Instant.parse("2026-01-01T10:05:00Z");
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "First", firstTime);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Second", secondTime);

    // When
    final List<ExecutionSummary> actualSummaries = reader.listExecutions(SESSION_ID_456).block();

    // Then - endTime should be last entry's timestamp
    final LocalDateTime expectedEndTime =
        ZonedDateTime.ofInstant(secondTime, ZoneId.systemDefault()).toLocalDateTime();
    assertThat(actualSummaries).hasSize(1);
    assertThat(actualSummaries.getFirst().endTime()).isEqualTo(expectedEndTime);
  }

  // ===== getRawContent Tests =====

  @Test
  void getRawContent_executionWithEntries_returnsFormattedString() {
    // Given
    final Instant timestamp1 = Instant.parse("2026-01-01T10:00:00Z");
    final Instant timestamp2 = Instant.parse("2026-01-01T10:01:00Z");
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Line 1", timestamp1);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_2, PLUGIN_NAME_2, "Line 2", timestamp2);

    // When
    final String actualContent = reader.getRawContent(EXECUTION_ID_001).block();

    // Then
    assertThat(actualContent).contains("Line 1");
    assertThat(actualContent).contains("Line 2");
    assertThat(actualContent).contains("|");
    assertThat(actualContent).contains("\n");
  }

  @Test
  void getRawContent_executionIdNotExists_returnsEmptyString() {
    // Given: storage does not contain executionId
    // When
    final String actualContent = reader.getRawContent("non-existent-execution").block();

    // Then
    assertThat(actualContent).isEmpty();
  }

  @Test
  void getRawContent_singleEntry_includesTimestampStreamPluginId() {
    // Given
    final Instant timestamp = Instant.parse("2026-01-01T10:30:45Z");
    addEntry(
        EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Test message", timestamp);

    // When
    final String actualContent = reader.getRawContent(EXECUTION_ID_001).block();

    // Then - format is: timestamp | stream | pluginId | message
    assertThat(actualContent)
        .contains("2026-01-01T10:30:45Z")
        .contains("STDOUT")
        .contains(PLUGIN_ID_1)
        .contains("Test message");
  }

  @Test
  void getRawContent_multipleEntries_joinedByNewlines() {
    // Given
    final Instant now = Instant.now();
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "First line", now);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Second line", now);
    addEntry(EXECUTION_ID_001, SESSION_ID_456, PLUGIN_ID_1, PLUGIN_NAME_1, "Third line", now);

    // When
    final String actualContent =
        Objects.requireNonNull(reader.getRawContent(EXECUTION_ID_001).block());

    // Then - entries should be separated by newlines
    final String[] lines = actualContent.split("\n");
    assertThat(lines).hasSize(3);
    assertThat(lines[0]).contains("First line");
    assertThat(lines[1]).contains("Second line");
    assertThat(lines[2]).contains("Third line");
  }

  @Test
  void getRawContent_differentLogStreams_included() {
    // Given
    final Instant now = Instant.now();
    final PluginLogEntry stdoutEntry =
        new PluginLogEntry(
            EXECUTION_ID_001,
            SESSION_ID_456,
            PLUGIN_ID_1,
            PLUGIN_NAME_1,
            LogStream.STDOUT,
            "stdout message",
            LogLevel.INFO,
            now,
            null,
            null);
    final PluginLogEntry stderrEntry =
        new PluginLogEntry(
            EXECUTION_ID_001,
            SESSION_ID_456,
            PLUGIN_ID_1,
            PLUGIN_NAME_1,
            LogStream.STDERR,
            "stderr message",
            LogLevel.ERROR,
            now,
            null,
            null);
    storage.computeIfAbsent(EXECUTION_ID_001, _ -> new ArrayList<>()).add(stdoutEntry);
    storage.computeIfAbsent(EXECUTION_ID_001, _ -> new ArrayList<>()).add(stderrEntry);

    // When
    final String actualContent = reader.getRawContent(EXECUTION_ID_001).block();

    // Then
    assertThat(actualContent).contains("STDOUT").contains("STDERR");
  }
}
