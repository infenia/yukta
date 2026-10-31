// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the sampler settings and turns on percentiles for the run timers. */
@Configuration
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfiguration {

  /** Timers that get p50, p95 and p99. */
  private static final Set<String> PERCENTILE_TIMERS =
      Set.of("yukta.execution.duration", "yukta.execution.queue.wait");

  /**
   * Adds client-side percentiles to the run duration and queue wait timers.
   *
   * @return the filter
   */
  @Bean
  public MeterFilter yuktaPercentiles() {
    return new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(
          final Meter.Id id, final DistributionStatisticConfig config) {
        return PERCENTILE_TIMERS.contains(id.getName())
            ? DistributionStatisticConfig.builder()
                .percentiles(0.5, 0.95, 0.99)
                .build()
                .merge(config)
            : config;
      }
    };
  }
}
