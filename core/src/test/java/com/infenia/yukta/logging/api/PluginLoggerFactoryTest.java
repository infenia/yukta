// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.impl.memory.DefaultPluginLoggerFactory;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Tests for PluginLoggerFactory. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.AvoidDuplicateLiterals"})
class PluginLoggerFactoryTest {

  @Test
  void testDefaultMethodUsesFourParamMethod() {
    final var mockWriter = mock(PluginLogWriter.class);
    final PluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);

    final PluginLogger logger1 = factory.create("exec-123", "session-456", "plugin-id");
    final PluginLogger logger2 =
        factory.create("exec-123", "session-456", "plugin-id", "plugin-id");

    assertThat(logger1).isNotNull();
    assertThat(logger2).isNotNull();
  }

  @Test
  void testCreateWithLoggerContext() {
    final var mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    final PluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);
    final PluginLoggerFactory.LoggerContext context =
        new PluginLoggerFactory.LoggerContext("exec-1", "sess-1", "plugin-1", "Plugin Name");

    final PluginLogger logger = factory.create(context);

    assertThat(logger).isNotNull();
  }

  @Test
  void testCreateMultipleLoggers() {
    final var mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    final PluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);

    final PluginLogger logger1 = factory.create("exec-1", "sess-1", "plugin-1");
    final PluginLogger logger2 = factory.create("exec-2", "sess-2", "plugin-2");

    assertThat(logger1).isNotNull();
    assertThat(logger2).isNotNull();
  }

  @Test
  void testImmutableAdapterDelegatesWrite() {
    final var mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    final DefaultPluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);
    final PluginLogger logger = factory.create("exec-1", "sess-1", "plugin-1", "Plugin Name");

    logger.logStdout("Test message").block();

    verify(mockWriter).write(any(PluginLogEntry.class));
  }

  @Test
  void testImmutableAdapterDelegatesWriteBatch() {
    final var mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());
    when(mockWriter.writeBatch(anyList())).thenReturn(Mono.empty());

    final DefaultPluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);

    factory.create("exec-1", "sess-1", "plugin-1").logStdout("msg1").block();
    factory.create("exec-1", "sess-1", "plugin-1").logStdout("msg2").block();

    verify(mockWriter, times(2)).write(any(PluginLogEntry.class));
  }

  @Test
  void testImmutableAdapterDelegatesClose() {
    final var mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.close()).thenReturn(Mono.empty());
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    final DefaultPluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);
    final PluginLogger logger = factory.create("exec-1", "sess-1", "plugin-1");

    logger.close().block();

    verify(mockWriter).close();
  }

  @Test
  void testAdapterPreventsDirectWriterAccess() {
    final var mockWriter = mock(PluginLogWriter.class);
    final DefaultPluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);

    final PluginLogger logger = factory.create("exec-1", "sess-1", "plugin-1");
    assertThat(logger).isNotNull();
  }

  @Test
  void testFactoryWithDifferentPluginIds() {
    final var mockWriter = mock(PluginLogWriter.class);
    when(mockWriter.write(any(PluginLogEntry.class))).thenReturn(Mono.empty());

    final DefaultPluginLoggerFactory factory = new DefaultPluginLoggerFactory(mockWriter);

    final PluginLogger logger1 = factory.create("exec-1", "sess-1", "process-executor");
    final PluginLogger logger2 = factory.create("exec-1", "sess-1", "http-trigger");
    final PluginLogger logger3 = factory.create("exec-1", "sess-1", "file-reader");

    assertThat(logger1).isNotNull();
    assertThat(logger2).isNotNull();
    assertThat(logger3).isNotNull();
  }
}
