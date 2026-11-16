// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

/**
 * What an {@link AgentEvent} describes. The keys of its {@code data} map are fixed per kind; see
 * the agent console plan.
 *
 * @since 0.1.0-alpha.4
 */
public enum AgentEventKind {
  /** The conversation changed state. */
  STATE,
  /** A piece of a message of the user, the agent or the agent's reasoning. */
  MESSAGE,
  /** A tool call and its progress. */
  TOOL,
  /** The agent's plan. */
  PLAN,
  /** An input request the agent raised, and what became of it. */
  REQUEST,
  /** Terminal output, or the settled screen of a terminal. */
  TERMINAL,
  /** The result of a turn. Appending one counts a finished turn. */
  RESULT,
  /** Something a person should know: stderr lines, protocol errors, a queued message. */
  NOTICE
}
