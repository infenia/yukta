// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the metrics sampler.
 *
 * @param enabled whether the sampler runs; the endpoints answer with empty data when it does not
 * @param interval time between samples
 * @param fineRetention how long samples at {@code interval} are kept
 * @param coarseRetention how long the one-per-minute samples are kept
 * @param maxSeries most series kept, so tag cardinality cannot grow the buffers
 * @param allowList meter name prefixes to sample
 */
@ConfigurationProperties(prefix = "yukta.metrics")
public record MetricsProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("5s") Duration interval,
    @DefaultValue("1h") Duration fineRetention,
    @DefaultValue("24h") Duration coarseRetention,
    @DefaultValue("500") int maxSeries,
    List<String> allowList) {

  /** The fewest series that make a sample. */
  private static final int MIN_SERIES = 1;

  /** Meter name prefixes sampled when {@code yukta.metrics.allow-list} is not set. */
  private static final List<String> DEFAULT_ALLOW_LIST =
      List.of(
          "yukta.",
          "llm.",
          "jvm.memory.used",
          "jvm.memory.max",
          "jvm.threads.live",
          "jvm.gc.pause",
          "process.cpu.usage",
          "system.cpu.usage");

  private static void requirePositive(final String name, final Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("yukta.metrics." + name + " must be positive");
    }
  }

  /** Falls back to the default allow-list when none is configured. */
  public MetricsProperties {
    requirePositive("interval", interval);
    if (fineRetention.compareTo(interval) < 0) {
      throw new IllegalArgumentException(
          "yukta.metrics.fine-retention must not be shorter than the interval");
    }
    if (coarseRetention.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException("yukta.metrics.coarse-retention must be at least 1m");
    }
    if (maxSeries < MIN_SERIES) {
      throw new IllegalArgumentException("yukta.metrics.max-series must be at least " + MIN_SERIES);
    }
    allowList =
        allowList == null || allowList.isEmpty() ? DEFAULT_ALLOW_LIST : List.copyOf(allowList);
  }
}
