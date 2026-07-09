// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

/** Categories of plugins to ensure predictable data flow. */
public enum PluginCategory {
  /** Sources: Producers of data. */
  TRIGGER,
  /** Transformers: Consumers AND Producers of data. */
  PROCESSOR,
  /** Sinks: Consumers only. */
  TERMINAL
}
