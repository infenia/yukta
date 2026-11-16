// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

/**
 * How Yukta talks to an agent. Clients never see the difference: they see messages, tools,
 * requests, a state and, for {@link #PTY}, a terminal.
 *
 * @since 0.1.0-alpha.4
 */
public enum AgentProtocol {
  /** JSON lines on standard input and output, as the Claude Code and Codex presets do. */
  JSONL,
  /** The Agent Client Protocol: JSON-RPC over standard input and output. */
  ACP,
  /** A pseudo-terminal that mirrors the program's own terminal interface. */
  PTY
}
