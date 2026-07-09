// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.memory;

import com.infenia.yukta.logging.api.PluginLogWriter;
import com.infenia.yukta.logging.api.PluginLogger;
import com.infenia.yukta.logging.api.PluginLoggerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of PluginLoggerFactory.
 *
 * <p>Creates DefaultPluginLogger instances configured for specific execution contexts.
 */
@Slf4j
public class DefaultPluginLoggerFactory implements PluginLoggerFactory {

  /** The underlying log writer. */
  private final PluginLogWriter writer;

  /**
   * Create a new DefaultPluginLoggerFactory.
   *
   * @param writer the log writer
   */
  public DefaultPluginLoggerFactory(final PluginLogWriter writer) {
    this.writer = new ImmutablePluginLogWriterAdapter(writer);
  }

  @Override
  public PluginLogger create(final PluginLoggerFactory.LoggerContext context) {
    log.atDebug()
        .addKeyValue("executionId", context.executionId())
        .addKeyValue("pluginId", context.pluginId())
        .log("Creating PluginLogger instance");
    return new DefaultPluginLogger(
        context.executionId(),
        context.sessionId(),
        context.pluginId(),
        context.pluginName(),
        writer);
  }

  /** Immutable adapter for PluginLogWriter to prevent EI2 exposure violations. */
  private static final class ImmutablePluginLogWriterAdapter implements PluginLogWriter {
    /** The underlying writer delegate. */
    private final PluginLogWriter delegate;

    /**
     * Package-private constructor to prevent external instantiation outside this class hierarchy.
     */
    /* default */ ImmutablePluginLogWriterAdapter(final PluginLogWriter delegate) {
      this.delegate = delegate;
    }

    @Override
    public reactor.core.publisher.Mono<Void> write(
        final com.infenia.yukta.logging.api.PluginLogEntry entry) {
      return delegate.write(entry);
    }

    @Override
    public reactor.core.publisher.Mono<Void> writeBatch(
        final java.util.List<com.infenia.yukta.logging.api.PluginLogEntry> entries) {
      return delegate.writeBatch(entries);
    }

    @Override
    public reactor.core.publisher.Mono<Void> close() {
      return delegate.close();
    }
  }
}
