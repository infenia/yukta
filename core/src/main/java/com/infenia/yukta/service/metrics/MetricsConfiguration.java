// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the sampler settings, turns on percentiles for the run timers and adds host memory. */
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

  /**
   * Binds the machine's memory next to the JVM's: {@code yukta.system.memory.total} and {@code
   * yukta.system.memory.free} in bytes, so the dashboard can show why the heap is not the machine's
   * RAM. Inside a container these are the container's limits.
   *
   * @return the binder
   */
  @Bean
  public MeterBinder yuktaHostMemory() {
    return hostMemory(ManagementFactory.getOperatingSystemMXBean());
  }

  /**
   * Binds the host memory gauges of an operating system bean, or nothing when it does not report
   * memory (only the HotSpot bean does).
   *
   * @param os the operating system bean
   * @return the binder
   */
  /* default */ static MeterBinder hostMemory(final OperatingSystemMXBean os) {
    return new HostMemory(
        os instanceof final com.sun.management.OperatingSystemMXBean host ? host : null);
  }

  /**
   * The host memory binder as a named class, so the bean's type is stable across runs (a lambda's
   * generated class name is not).
   *
   * @param host the HotSpot bean, or null when the OS does not report memory
   */
  private record HostMemory(com.sun.management.OperatingSystemMXBean host) implements MeterBinder {
    @Override
    public void bindTo(final MeterRegistry registry) {
      if (host == null) {
        return;
      }
      Gauge.builder(
              "yukta.system.memory.total",
              host,
              com.sun.management.OperatingSystemMXBean::getTotalMemorySize)
          .baseUnit("bytes")
          .description("Physical memory of the host or container")
          .register(registry);
      Gauge.builder(
              "yukta.system.memory.free",
              host,
              com.sun.management.OperatingSystemMXBean::getFreeMemorySize)
          .baseUnit("bytes")
          .description("Physical memory not in use")
          .register(registry);
    }
  }
}
