// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Publishes how many executions are running and how many wait for a slot. */
@Component
@RequiredArgsConstructor
public class SchedulerMetrics implements MeterBinder {

  /** The scheduler to observe. */
  private final ExecutionScheduler scheduler;

  @Override
  public void bindTo(final MeterRegistry registry) {
    Gauge.builder("yukta.executions.running", scheduler, ExecutionScheduler::running)
        .description("Executions holding a slot")
        .register(registry);
    Gauge.builder("yukta.executions.queued", scheduler, ExecutionScheduler::queued)
        .description("Executions waiting for a slot")
        .register(registry);
  }
}
