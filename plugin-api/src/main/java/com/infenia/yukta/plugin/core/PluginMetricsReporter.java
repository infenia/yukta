// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

/** Interface for reporting plugin execution metrics. */
@FunctionalInterface
public interface PluginMetricsReporter {
  /**
   * Increment the count of messages processed by a filter.
   *
   * @param nodeId the ID of the filter node
   * @param status the filter status (e.g., "MATCH", "DISCARD", "ERROR")
   */
  void incrementFilterCount(String nodeId, String status);
}
