// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

/** Interface for reporting plugin execution metrics. */
@FunctionalInterface
public interface PluginMetricsReporter {

  /** Reporter that drops every count; used when no metrics backend is configured. */
  PluginMetricsReporter NOOP = (nodeId, metric, tag) -> {};

  /**
   * Increment a per-node counter.
   *
   * @param nodeId the ID of the node
   * @param metric the metric name (e.g., "filter")
   * @param tag the outcome the count is tagged with (e.g., "MATCH", "DISCARD", "ERROR")
   */
  void increment(String nodeId, String metric, String tag);
}
