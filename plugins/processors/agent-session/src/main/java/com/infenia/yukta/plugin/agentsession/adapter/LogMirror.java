// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.core.PluginLogSink;
import org.jspecify.annotations.Nullable;

/**
 * Writes what the agent says and does to the run log, as the plugin always has: lines of the agent
 * under the stream {@code agent}, everything else as it came. The run log is unchanged by
 * conversations, so `yukta-cli logs` and the Logs panel keep working.
 */
final class LogMirror {

  /** The stream of the lines of the agent. */
  private static final String AGENT_STREAM = "agent";

  /** The run log. */
  private final PluginLogSink downstream;

  /* default */ LogMirror(final PluginLogSink downstream) {
    this.downstream = downstream;
  }

  /** Writes a line of the agent: its text, or a tool call with the prefix of the rule. */
  /* default */ void agent(final String text, final @Nullable LogLevel level) {
    downstream.line(AGENT_STREAM, text, level);
  }

  /**
   * Writes a line that is not an event of the agent (standard error, text that is not JSON). Before
   * conversations standard error was merged into standard output, so its lines stay under the
   * stream {@code stdout} in the run log.
   */
  /* default */ void passThrough(
      final String stream, final String line, final @Nullable LogLevel level) {
    downstream.line("stderr".equals(stream) ? "stdout" : stream, line, level);
  }
}
