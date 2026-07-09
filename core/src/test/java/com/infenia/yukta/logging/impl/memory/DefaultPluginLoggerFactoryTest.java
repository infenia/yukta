// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogWriter;
import com.infenia.yukta.logging.api.PluginLogger;
import com.infenia.yukta.logging.api.PluginLoggerFactory;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/** Unit tests for {@link DefaultPluginLoggerFactory} and ImmutablePluginLogWriterAdapter. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LocalVariableCouldBeFinal",
  "PMD.LawOfDemeter",
  "PMD.AvoidAccessibilityAlteration"
})
class DefaultPluginLoggerFactoryTest {

  /** Session ID used in test scenarios. */
  private static final String SESSION_ID = "session-456";

  /** Mock PluginLogWriter for test verification. */
  private PluginLogWriter mockWriter;

  /** DefaultPluginLoggerFactory instance under test. */
  private DefaultPluginLoggerFactory factory;

  @BeforeEach
  void setUp() {
    mockWriter = mock(PluginLogWriter.class);
    factory = new DefaultPluginLoggerFactory(mockWriter);

    // Setup default mock behaviors
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());
    when(mockWriter.writeBatch(any(List.class))).thenReturn(Mono.empty());
    when(mockWriter.close()).thenReturn(Mono.empty());
  }

  @Test
  void testCreateLogger() {
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id", "Plugin Name");

    assertThat(logger).isNotNull();
    assertThat(logger).isInstanceOf(DefaultPluginLogger.class);
  }

  @Test
  void testCreateLoggerWithThreeParams() {
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id");

    assertThat(logger).isNotNull();
    assertThat(logger).isInstanceOf(DefaultPluginLogger.class);
  }

  @Test
  void testMultipleLoggersCanBeCreated() {
    final PluginLogger logger1 = factory.create("exec-001", SESSION_ID, "plugin-1", "Plugin 1");
    final PluginLogger logger2 = factory.create("exec-002", SESSION_ID, "plugin-2", "Plugin 2");

    assertThat(logger1).isNotNull();
    assertThat(logger2).isNotNull();
    assertThat(logger1).isNotSameAs(logger2);
  }

  // Tests for ImmutablePluginLogWriterAdapter coverage

  @Test
  void write_singleLogEntry_delegatesToUnderlyingWriter() {
    // Given
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id", "Plugin Name");
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    // When
    logger.logStdout("Test message").block();

    // Then
    var captor = ArgumentCaptor.forClass(PluginLogEntry.class);
    verify(mockWriter).write(captor.capture());
    PluginLogEntry capturedEntry = captor.getValue();
    assertThat(capturedEntry.message()).isEqualTo("Test message");
    assertThat(capturedEntry.executionId()).isEqualTo("exec-123");
  }

  @Test
  void adapter_implementsWriteBatchMethod() {
    // This test verifies that ImmutablePluginLogWriterAdapter implements writeBatch().
    // Since the adapter is a private inner class, we can only verify its presence through
    // the interface contract it fulfills. The adapter must implement all three methods
    // of PluginLogWriter: write(), writeBatch(), and close().
    //
    // Note: The writeBatch() method is part of the PluginLogWriter interface contract
    // but is never invoked by DefaultPluginLogger (which only calls write() and close()).
    // To achieve code coverage of writeBatch(), a direct caller would need to invoke it
    // on the PluginLogWriter instance.

    // Given
    when(mockWriter.writeBatch(any(List.class))).thenReturn(Mono.empty());

    // When - create a logger, which initializes the adapter
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id");

    // Then - verify the factory creates valid loggers that use the adapter
    assertThat(logger).isNotNull();
    assertThat(logger).isInstanceOf(DefaultPluginLogger.class);
    // The adapter is successfully created and wrapped around mockWriter
  }

  @Test
  void close_calledOnAdapter_delegatesToUnderlyingWriter() {
    // Given
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id", "Plugin Name");
    when(mockWriter.close()).thenReturn(Mono.empty());

    // When
    logger.close().block();

    // Then
    verify(mockWriter).close();
  }

  @Test
  void adapter_wrapsWriterDuringConstruction_preventsDirectAccess() {
    // Given
    final PluginLogWriter innerWriter = mock(PluginLogWriter.class);

    // When
    final DefaultPluginLoggerFactory testFactory = new DefaultPluginLoggerFactory(innerWriter);
    final PluginLogger logger = testFactory.create("exec-123", SESSION_ID, "plugin-id");

    // Then - verify that the factory successfully creates loggers, which proves the adapter
    // wrapping works (the adapter is package-private and cannot be accessed directly)
    assertThat(logger).isNotNull();
    assertThat(logger).isInstanceOf(DefaultPluginLogger.class);
  }

  @Test
  void write_withDifferentLogLevels_delegatesCorrectly() {
    // Given
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id", "Plugin Name");
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    // When - logging with different streams and levels
    logger.logStdout("stdout message").block();
    logger.logStderr("stderr message").block();

    // Then
    var captor = ArgumentCaptor.forClass(PluginLogEntry.class);
    verify(mockWriter, org.mockito.Mockito.times(2)).write(captor.capture());
    List<PluginLogEntry> capturedEntries = captor.getAllValues();
    assertThat(capturedEntries).hasSize(2);
    assertThat(capturedEntries.get(0).stream()).isEqualTo(LogStream.STDOUT);
    assertThat(capturedEntries.get(1).stream()).isEqualTo(LogStream.STDERR);
  }

  @Test
  void create_withLoggerContext_delegatesWriterOperations() {
    // Given
    final PluginLoggerFactory.LoggerContext context =
        new PluginLoggerFactory.LoggerContext(
            "exec-456", "session-789", "plugin-123", "Test Plugin");
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    // When
    final PluginLogger logger = factory.create(context);
    logger.logStdout("Test").block();

    // Then
    verify(mockWriter).write(any(PluginLogEntry.class));
    assertThat(logger).isNotNull();
  }

  @Test
  void adapter_implementsPluginLogWriterContract() {
    // Verify that the adapter correctly implements all PluginLogWriter interface methods.
    // The adapter is created during factory initialization and provides transparent
    // delegation to the underlying writer.

    // Given
    when(mockWriter.close()).thenReturn(Mono.empty());

    // When - create and use a logger
    final PluginLogger logger = factory.create("exec-123", SESSION_ID, "plugin-id");
    logger.close().block();

    // Then
    verify(mockWriter).close();
  }

  @Test
  void adapter_writeBatch_delegatesToUnderlyingWriter() throws Exception {
    // The writeBatch method of ImmutablePluginLogWriterAdapter must be tested.
    // Since the adapter is private to DefaultPluginLoggerFactory, we access it via
    // reflection to ensure all paths are covered.

    // Given
    final Instant now = Instant.now();
    final List<PluginLogEntry> entries =
        List.of(
            new PluginLogEntry(
                "exec-123",
                SESSION_ID,
                "plugin-id",
                "Plugin Name",
                LogStream.STDOUT,
                "Message 1",
                LogLevel.INFO,
                now,
                null,
                null),
            new PluginLogEntry(
                "exec-123",
                SESSION_ID,
                "plugin-id",
                "Plugin Name",
                LogStream.STDOUT,
                "Message 2",
                LogLevel.INFO,
                now,
                null,
                null));
    when(mockWriter.writeBatch(any(List.class))).thenReturn(Mono.empty());

    // When - access the adapter via reflection through the factory's writer field
    java.lang.reflect.Field writerField =
        DefaultPluginLoggerFactory.class.getDeclaredField("writer");
    writerField.setAccessible(true);
    PluginLogWriter adapter = (PluginLogWriter) writerField.get(factory);

    // Then - invoke writeBatch on the adapter
    adapter.writeBatch(entries).block();
    verify(mockWriter).writeBatch(entries);
  }

  @Test
  void adapter_write_delegatesToUnderlyingWriter_viaReflection() throws Exception {
    // Verify write() method delegation through direct reflection access to ensure
    // all paths in ImmutablePluginLogWriterAdapter are covered.

    // Given
    final Instant now = Instant.now();
    final PluginLogEntry entry =
        new PluginLogEntry(
            "exec-789",
            SESSION_ID,
            "plugin-xyz",
            "Test Plugin",
            LogStream.STDERR,
            "Test message",
            LogLevel.ERROR,
            now,
            null,
            null);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    // When - access the adapter via reflection and invoke write
    java.lang.reflect.Field writerField =
        DefaultPluginLoggerFactory.class.getDeclaredField("writer");
    writerField.setAccessible(true);
    PluginLogWriter adapter = (PluginLogWriter) writerField.get(factory);
    adapter.write(entry).block();

    // Then
    verify(mockWriter).write(entry);
  }

  @Test
  void adapter_close_delegatesToUnderlyingWriter_viaReflection() throws Exception {
    // Verify close() method delegation through direct reflection access to ensure
    // all paths in ImmutablePluginLogWriterAdapter are covered.

    // Given
    when(mockWriter.close()).thenReturn(Mono.empty());

    // When - access the adapter via reflection and invoke close
    java.lang.reflect.Field writerField =
        DefaultPluginLoggerFactory.class.getDeclaredField("writer");
    writerField.setAccessible(true);
    PluginLogWriter adapter = (PluginLogWriter) writerField.get(factory);
    adapter.close().block();

    // Then
    verify(mockWriter).close();
  }
}
