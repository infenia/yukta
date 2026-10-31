// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.Map;

/**
 * The newest metrics of the server or of one session or workflow.
 *
 * @param scope {@code global}, {@code session:<id>} or {@code workflow:<id>}
 * @param time epoch milliseconds of the sample, 0 before the first one
 * @param values series key to value: counters as a rate per second, timers in milliseconds, run
 *     aggregates ({@code runs.*}, {@code duration.p95}) over the last hour
 */
public record MetricsSnapshot(String scope, long time, Map<String, Double> values) {

  /** Keeps an immutable copy of the values. */
  public MetricsSnapshot {
    values = Map.copyOf(values);
  }
}
