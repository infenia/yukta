// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.MetricsSnapshot;
import com.infenia.yukta.service.metrics.MetricSample;
import com.infenia.yukta.service.metrics.MetricsSampler;
import com.infenia.yukta.service.metrics.RunAnalytics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reads the server's metrics for MCP tools. */
@Component
@RequiredArgsConstructor
public class MetricsProvider {

  /** The sampled meters. */
  private final MetricsSampler sampler;

  /** The per-session and per-workflow run aggregates. */
  private final RunAnalytics analytics;

  /**
   * The newest metrics.
   *
   * @param scope {@code global}, {@code session:<id>} or {@code workflow:<id>}; null means global.
   *     A scope other than global has only the run aggregates, and an unknown scope has none.
   * @return the values
   */
  public Mono<MetricsSnapshot> getMetricsSnapshot(final String scope) {
    final String wanted = scope == null || scope.isBlank() ? RunAnalytics.GLOBAL : scope.strip();
    final Mono<MetricSample> sampled =
        RunAnalytics.GLOBAL.equals(wanted)
            ? sampler.snapshot().defaultIfEmpty(new MetricSample(0, Map.of()))
            : Mono.just(new MetricSample(0, Map.of()));
    return sampled.map(
        sample -> {
          final Map<String, Double> values = new ConcurrentHashMap<>(sample.values());
          values.putAll(analytics.snapshot(wanted));
          return new MetricsSnapshot(wanted, sample.time(), values);
        });
  }
}
