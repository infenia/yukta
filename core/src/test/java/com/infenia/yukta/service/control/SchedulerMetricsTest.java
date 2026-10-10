// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class SchedulerMetricsTest {

  @Test
  void bindTo_reportsRunningAndQueuedExecutions() {
    final ExecutionScheduler scheduler =
        new ExecutionScheduler(1, 0, 10, ExecutionScheduler.OnLimit.QUEUE, Duration.ofSeconds(1));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new SchedulerMetrics(scheduler).bindTo(registry);

    final var running = scheduler.acquire("s", "w");
    final var waiting = scheduler.acquire("s", "w");
    waiting.permit().subscribe();

    assertThat(registry.get("yukta.executions.running").gauge().value()).isEqualTo(1.0);
    assertThat(registry.get("yukta.executions.queued").gauge().value()).isEqualTo(1.0);
    java.util.Objects.requireNonNull(running.permit().block()).release();
    assertThat(registry.get("yukta.executions.queued").gauge().value()).isZero();
  }
}
