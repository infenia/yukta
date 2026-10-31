// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import java.util.Map;

/**
 * Every sampled value at one instant.
 *
 * @param time epoch milliseconds
 * @param values series key to value; counters as a rate per second ({@code |rate}), timers as
 *     {@code |rate}, {@code |mean} (ms), {@code |max} (ms) and {@code |p50}, {@code |p95}, {@code
 *     |p99} (ms)
 */
public record MetricSample(long time, Map<String, Double> values) {

  /** Keeps an immutable copy of the values. */
  public MetricSample {
    values = Map.copyOf(values);
  }
}
