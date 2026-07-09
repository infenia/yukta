// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import java.util.Map;

/**
 * Standard statistics message for the Control Bus.
 *
 * @param nodeId the unique identifier of the node
 * @param throughput messages processed per second
 * @param latency average latency per message in milliseconds
 * @param metrics additional custom metrics provided by the plugin
 */
public record ControlStatistics(
    String nodeId, double throughput, double latency, Map<String, Object> metrics) {
  /** Compact constructor. */
  public ControlStatistics {
    metrics = Map.copyOf(metrics);
  }

  /** Create a new statistics record with empty metrics. */
  public ControlStatistics(final String nodeId, final double throughput, final double latency) {
    this(nodeId, throughput, latency, Map.of());
  }
}
