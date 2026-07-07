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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for {@link DefaultPluginLogger}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidAccessibilityAlteration",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidDuplicateLiterals"
})
class DefaultPluginLoggerTest {

  /** Execution ID for testing. */
  private static final String EXEC_ID = "exec-123";

  /** Session ID for testing. */
  private static final String SESSION_ID = "session-456";

  /** Process executor plugin ID for testing. */
  private static final String PROCESS_EXECUTOR_ID = "process-executor";

  /** Process executor plugin name for testing. */
  private static final String PROCESS_EXECUTOR_NAME = "Process Executor";

  /** Docker build custom stream name for testing. */
  private static final String DOCKER_BUILD = "docker-build";

  /** Docker plugin ID for testing. */
  private static final String DOCKER_PLUGIN = "docker-plugin";

  /** Docker plugin name for testing. */
  private static final String DOCKER_PLUGIN_NAME = "Docker Plugin";

  /** Custom message literal for testing. */
  private static final String CUSTOM_MESSAGE = "Custom message";

  /** Test plugin ID for testing. */
  private static final String TEST_PLUGIN = "test-plugin";

  /** Test plugin name for testing. */
  private static final String TEST_PLUGIN_NAME = "Test Plugin";

  /** Mock PluginLogWriter for test verification. */
  private PluginLogWriter mockWriter;

  /** DefaultPluginLogger instance under test. */
  private DefaultPluginLogger logger;

  @BeforeEach
  void setUp() {
    mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    logger =
        new DefaultPluginLogger(
            EXEC_ID, SESSION_ID, PROCESS_EXECUTOR_ID, PROCESS_EXECUTOR_NAME, mockWriter);
  }

  @Test
  void testLogStdout() {
    StepVerifier.create(logger.logStdout("Hello World")).verifyComplete();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testLogStdoutWithMetadata() {
    final Map<String, Object> metadata = Map.of("key", "value");

    StepVerifier.create(logger.logStdout("Message", metadata)).verifyComplete();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testLogStdoutWithMetadata_capturesMetadata() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);
    final Map<String, Object> metadata = Map.of("userId", "user-123", "requestId", "req-456");

    StepVerifier.create(logger.logStdout("User action logged", metadata)).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.metadata()).containsExactlyInAnyOrderEntriesOf(metadata);
  }

  @Test
  void testLogStderr() {
    StepVerifier.create(logger.logStderr("Error message")).verifyComplete();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testLogCustom() {
    StepVerifier.create(logger.logCustom(DOCKER_BUILD, CUSTOM_MESSAGE)).verifyComplete();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testLogCustom_preservesCustomStreamName() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);

    StepVerifier.create(logger.logCustom(DOCKER_BUILD, "Building image")).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.customStreamName()).isEqualTo(DOCKER_BUILD);
  }

  @Test
  void testLogCustomWithMetadata() {
    final Map<String, Object> metadata = Map.of("key", "value");

    StepVerifier.create(logger.logCustom("custom-stream", CUSTOM_MESSAGE, metadata))
        .verifyComplete();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testLogCustomWithMetadata_capturesMetadataAndStreamName() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);
    final Map<String, Object> metadata = Map.of("layer", "final", "size", "2GB");

    StepVerifier.create(logger.logCustom(DOCKER_BUILD, "Layer completed", metadata))
        .verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.customStreamName()).isEqualTo(DOCKER_BUILD);
    Assertions.assertThat(capturedEntry.metadata()).containsExactlyInAnyOrderEntriesOf(metadata);
  }

  @Test
  void testLogStderrWithMetadata() {
    final Map<String, Object> metadata = Map.of("error", "details");

    StepVerifier.create(logger.logStderr("Error with metadata", metadata)).verifyComplete();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testClose() {
    when(mockWriter.close()).thenReturn(Mono.empty());

    StepVerifier.create(logger.close()).verifyComplete();

    verify(mockWriter).close();
  }

  private PluginLogWriter getAdapter() throws NoSuchFieldException, IllegalAccessException {
    final java.lang.reflect.Field writerField =
        DefaultPluginLogger.class.getDeclaredField("writer");
    writerField.setAccessible(true);
    return (PluginLogWriter) writerField.get(logger);
  }

  /**
   * Reads the captured value off an {@link ArgumentCaptor}.
   *
   * <p>Isolated in its own method (with the captor as a parameter, not a foreign value) so callers
   * can freely call further methods on the returned entry without a PMD LawOfDemeter violation.
   *
   * @param captor the captor to read from
   * @return the captured value
   */
  private static PluginLogEntry captureValue(final ArgumentCaptor<PluginLogEntry> captor) {
    return captor.getValue();
  }

  @Test
  void adapter_writeBatch_delegatesToUnderlyingWriter() throws Exception {
    // The writeBatch method of ImmutablePluginLogWriterAdapter in DefaultPluginLogger
    // must be tested. Since the adapter is private, we access it via reflection to ensure
    // all paths are covered.

    // Given
    final List<PluginLogEntry> entries = List.of();
    when(mockWriter.writeBatch(any(List.class))).thenReturn(Mono.empty());

    // When
    final PluginLogWriter adapter = getAdapter();
    StepVerifier.create(adapter.writeBatch(entries)).verifyComplete();

    // Then
    verify(mockWriter).writeBatch(entries);
  }

  @Test
  void adapter_write_delegatesToUnderlyingWriter_viaReflection() throws Exception {
    // Verify write() method delegation through direct reflection access to ensure
    // all paths in DefaultPluginLogger's ImmutablePluginLogWriterAdapter are covered.

    // Given
    final Instant now = Instant.now();
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            PROCESS_EXECUTOR_ID,
            PROCESS_EXECUTOR_NAME,
            LogStream.STDERR,
            "Test message",
            LogLevel.ERROR,
            now,
            null,
            null);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    // When
    final PluginLogWriter adapter = getAdapter();
    StepVerifier.create(adapter.write(entry)).verifyComplete();

    // Then
    verify(mockWriter).write(entry);
  }

  @Test
  void adapter_close_delegatesToUnderlyingWriter_viaReflection() throws Exception {
    // Verify close() method delegation through direct reflection access to ensure
    // all paths in DefaultPluginLogger's ImmutablePluginLogWriterAdapter are covered.

    // Given
    when(mockWriter.close()).thenReturn(Mono.empty());

    // When
    final PluginLogWriter adapter = getAdapter();
    StepVerifier.create(adapter.close()).verifyComplete();

    // Then
    verify(mockWriter).close();
  }

  @Test
  void pluginLogEntry_format_includesMetadata() {
    final Map<String, Object> metadata =
        Map.of("requestId", "req-123", "status", 200, "duration", "1.5s");
    final Instant timestamp = Instant.parse("2026-07-07T10:30:00Z");
    final PluginLogEntry entry =
        new PluginLogEntry(
            "exec-123",
            "session-456",
            "process-executor",
            "Process Executor",
            LogStream.STDOUT,
            "Request completed",
            LogLevel.INFO,
            timestamp,
            "stdout",
            metadata);

    final String formatted = entry.format();

    Assertions.assertThat(formatted)
        .contains("2026-07-07T10:30:00Z")
        .contains("INFO")
        .contains("process-executor")
        .contains("Process Executor")
        .contains("STDOUT")
        .contains("Request completed")
        .contains("requestId=req-123")
        .contains("status=200")
        .contains("duration=1.5s");
  }

  @Test
  void pluginLogEntry_format_includesCustomStreamName() {
    final Instant timestamp = Instant.parse("2026-07-07T10:35:00Z");
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            DOCKER_PLUGIN,
            DOCKER_PLUGIN_NAME,
            LogStream.CUSTOM,
            "Image built successfully",
            LogLevel.INFO,
            timestamp,
            DOCKER_BUILD,
            Map.of());

    final String formatted = entry.format();

    Assertions.assertThat(formatted)
        .contains(DOCKER_BUILD)
        .contains("Image built successfully")
        .doesNotContain("CUSTOM");
  }

  @Test
  void pluginLogEntry_format_stdoutWithoutMetadata() {
    final Instant timestamp = Instant.parse("2026-07-07T11:00:00Z");
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            "test-plugin",
            "Test Plugin",
            LogStream.STDOUT,
            "Simple message",
            LogLevel.INFO,
            timestamp,
            null,
            null);

    final String formatted = entry.format();

    Assertions.assertThat(formatted)
        .contains("2026-07-07T11:00:00Z")
        .contains("INFO")
        .contains("test-plugin")
        .contains("Test Plugin")
        .contains("STDOUT")
        .contains("Simple message")
        .doesNotContain("{");
  }

  @Test
  void pluginLogEntry_format_customStreamWithNullCustomStreamName() {
    final Instant timestamp = Instant.parse("2026-07-07T11:05:00Z");
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            TEST_PLUGIN,
            TEST_PLUGIN_NAME,
            LogStream.CUSTOM,
            CUSTOM_MESSAGE,
            LogLevel.INFO,
            timestamp,
            null,
            null);

    final String formatted = entry.format();

    Assertions.assertThat(formatted)
        .contains("2026-07-07T11:05:00Z")
        .contains("INFO")
        .contains(TEST_PLUGIN)
        .contains(TEST_PLUGIN_NAME)
        .contains("CUSTOM")
        .contains(CUSTOM_MESSAGE);
  }

  @Test
  void pluginLogEntry_format_stderrWithEmptyMetadata() {
    final Instant timestamp = Instant.parse("2026-07-07T11:10:00Z");
    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            "error-plugin",
            "Error Plugin",
            LogStream.STDERR,
            "Error occurred",
            LogLevel.ERROR,
            timestamp,
            null,
            Map.of());

    final String formatted = entry.format();

    Assertions.assertThat(formatted)
        .contains("2026-07-07T11:10:00Z")
        .contains("ERROR")
        .contains("error-plugin")
        .contains("Error Plugin")
        .contains("STDERR")
        .contains("Error occurred")
        .doesNotContain("{");
  }

  @Test
  void testClose_withError() {
    final RuntimeException testException = new RuntimeException("Test error");
    when(mockWriter.close()).thenReturn(Mono.error(testException));

    StepVerifier.create(logger.close())
        .expectErrorMatches(
            error -> error instanceof RuntimeException && "Test error".equals(error.getMessage()))
        .verify();
  }

  @Test
  void testLogStderr_capturesLogLevel() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);

    StepVerifier.create(logger.logStderr("Error message")).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.logLevel()).isEqualTo(LogLevel.ERROR);
  }

  @Test
  void testLogStdout_capturesLogLevel() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);

    StepVerifier.create(logger.logStdout("Info message")).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.logLevel()).isEqualTo(LogLevel.INFO);
  }

  @Test
  void testLogCustom_capturesStreamAndLogLevel() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);

    StepVerifier.create(logger.logCustom("custom-stream", "Custom message")).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.stream()).isEqualTo(LogStream.CUSTOM);
    Assertions.assertThat(capturedEntry.logLevel()).isEqualTo(LogLevel.INFO);
  }

  @Test
  void testLogStderr_capturesStreamType() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);

    StepVerifier.create(logger.logStderr("Error")).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.stream()).isEqualTo(LogStream.STDERR);
  }

  @Test
  void testLogStdout_capturesStreamType() {
    final ArgumentCaptor<PluginLogEntry> captor = ArgumentCaptor.forClass(PluginLogEntry.class);

    StepVerifier.create(logger.logStdout("Output")).verifyComplete();

    verify(mockWriter).write(captor.capture());
    final PluginLogEntry capturedEntry = captureValue(captor);
    Assertions.assertThat(capturedEntry.stream()).isEqualTo(LogStream.STDOUT);
  }

  @Test
  void pluginLogEntry_defensiveCopyMetadata() {
    final Map<String, Object> originalMetadata = Map.of("key1", "value1");
    final Map<String, Object> mutableMetadata = new java.util.LinkedHashMap<>(originalMetadata);

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            TEST_PLUGIN,
            TEST_PLUGIN_NAME,
            LogStream.STDOUT,
            "Message",
            LogLevel.INFO,
            Instant.now(),
            null,
            mutableMetadata);

    mutableMetadata.put("key2", "value2");

    Assertions.assertThat(entry.metadata())
        .hasSize(1)
        .containsEntry("key1", "value1")
        .doesNotContainKey("key2");
  }

  @Test
  void pluginLogEntry_defensivelyFreezeNestedListMetadata() {
    final List<String> mutableList = new java.util.ArrayList<>();
    mutableList.add("item1");
    final Map<String, Object> metadata = Map.of("tags", mutableList);

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            TEST_PLUGIN,
            TEST_PLUGIN_NAME,
            LogStream.STDOUT,
            "Message",
            LogLevel.INFO,
            Instant.now(),
            null,
            metadata);

    mutableList.add("item2");

    @SuppressWarnings("unchecked")
    final List<String> capturedList = (List<String>) entry.metadata().get("tags");
    Assertions.assertThat(capturedList).hasSize(1).containsExactly("item1").doesNotContain("item2");
  }

  @Test
  void pluginLogEntry_defensivelyFreezeNestedMapMetadata() {
    final Map<String, String> mutableNestedMap = new java.util.HashMap<>();
    mutableNestedMap.put("setting1", "value1");
    final Map<String, Object> metadata = Map.of("config", mutableNestedMap);

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            TEST_PLUGIN,
            TEST_PLUGIN_NAME,
            LogStream.STDOUT,
            "Message",
            LogLevel.INFO,
            Instant.now(),
            null,
            metadata);

    mutableNestedMap.put("setting2", "value2");

    @SuppressWarnings("unchecked")
    final Map<String, String> capturedMap = (Map<String, String>) entry.metadata().get("config");
    Assertions.assertThat(capturedMap)
        .hasSize(1)
        .containsEntry("setting1", "value1")
        .doesNotContainKey("setting2");
  }

  @Test
  void pluginLogEntry_defensivelyFreezeNestedSetMetadata() {
    final java.util.Set<String> mutableSet = new java.util.HashSet<>();
    mutableSet.add("tag1");
    final Map<String, Object> metadata = Map.of("tags", mutableSet);

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            TEST_PLUGIN,
            TEST_PLUGIN_NAME,
            LogStream.STDOUT,
            "Message",
            LogLevel.INFO,
            Instant.now(),
            null,
            metadata);

    mutableSet.add("tag2");

    @SuppressWarnings("unchecked")
    final java.util.Set<String> capturedSet = (java.util.Set<String>) entry.metadata().get("tags");
    Assertions.assertThat(capturedSet).hasSize(1).containsExactly("tag1").doesNotContain("tag2");
  }

  @Test
  void pluginLogEntry_defensivelyFreezeDeepNestedMapWithListValues() {
    final List<String> mutableInnerList = new java.util.ArrayList<>();
    mutableInnerList.add("item1");
    final Map<String, Object> mutableInnerMap = new java.util.HashMap<>();
    mutableInnerMap.put("items", mutableInnerList);
    final Map<String, Object> metadata = Map.of("config", mutableInnerMap);

    final PluginLogEntry entry =
        new PluginLogEntry(
            EXEC_ID,
            SESSION_ID,
            TEST_PLUGIN,
            TEST_PLUGIN_NAME,
            LogStream.STDOUT,
            "Message",
            LogLevel.INFO,
            Instant.now(),
            null,
            metadata);

    mutableInnerList.add("item2");
    mutableInnerMap.put("items", new java.util.ArrayList<>());

    @SuppressWarnings("unchecked")
    final Map<String, Object> capturedInnerMap =
        (Map<String, Object>) entry.metadata().get("config");
    @SuppressWarnings("unchecked")
    final List<String> capturedInnerList = (List<String>) capturedInnerMap.get("items");
    Assertions.assertThat(capturedInnerList)
        .hasSize(1)
        .containsExactly("item1")
        .doesNotContain("item2");
  }
}
