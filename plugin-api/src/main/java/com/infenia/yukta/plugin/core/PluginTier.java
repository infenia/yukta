// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

/** How generic a plugin is. A default install ships {@link #CORE} and {@link #EXTENDED}. */
public enum PluginTier {
  /** Needed by any workflow: flow control, triggers, terminals, supervisors. */
  CORE,
  /** Generic I/O and helpers most deployments use. */
  EXTENDED,
  /** Tied to one vendor, protocol or use case (LLM, MCP, decisions, chat memory, Slack). */
  SPECIFIC
}
