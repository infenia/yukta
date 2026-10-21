// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import org.jspecify.annotations.Nullable;

/**
 * Receives log lines a plugin produces while it runs, so they reach the execution log live instead
 * of after the node finishes. A plugin finds its sink in the Reactor context under {@link
 * WorkflowContext#LOG_SINK_KEY}; it is absent outside a workflow run.
 */
@FunctionalInterface
public interface PluginLogSink {

  /** Sink that drops every line; used when no workflow run supplies one. */
  PluginLogSink NOOP = (stream, line, level) -> {};

  /**
   * Publish one log line.
   *
   * @param stream the stream the line came from ({@code "stdout"} or {@code "stderr"})
   * @param line the line, without its trailing newline; never truncated by the engine
   * @param level the line's level if the plugin knows it, or {@code null} to let the engine rate
   *     the line
   */
  void line(String stream, String line, @Nullable LogLevel level);

  /**
   * Publish one log line whose level the engine rates.
   *
   * @param stream the stream the line came from ({@code "stdout"} or {@code "stderr"})
   * @param line the line, without its trailing newline; never truncated by the engine
   */
  default void line(final String stream, final String line) {
    line(stream, line, null);
  }
}
