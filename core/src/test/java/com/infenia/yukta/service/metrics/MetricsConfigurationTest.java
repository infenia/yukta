// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.OperatingSystemMXBean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for MetricsConfiguration. */
@NoArgsConstructor
class MetricsConfigurationTest {

  @Test
  void reportsTheHostsMemoryNextToTheJvms() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new MetricsConfiguration().yuktaHostMemory().bindTo(registry);

    final double total = registry.get("yukta.system.memory.total").gauge().value();
    final double free = registry.get("yukta.system.memory.free").gauge().value();
    assertThat(total).isPositive();
    assertThat(free).isBetween(0.0, total);
  }

  @Test
  void bindsNothingWhenTheBeanDoesNotReportMemory() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MeterBinder binder = MetricsConfiguration.hostMemory(mock(OperatingSystemMXBean.class));

    binder.bindTo(registry);

    assertThat(registry.getMeters()).isEmpty();
  }
}
