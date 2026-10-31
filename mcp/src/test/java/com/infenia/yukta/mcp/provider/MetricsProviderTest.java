// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.metrics.MetricsProperties;
import com.infenia.yukta.service.metrics.MetricsSampler;
import com.infenia.yukta.service.metrics.RunAnalytics;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link MetricsProvider}. */
@NoArgsConstructor
class MetricsProviderTest {

  private static final String GAUGE = "yukta.g";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final MetricsSampler sampler =
      new MetricsSampler(
          registry,
          new MetricsProperties(
              false,
              Duration.ofSeconds(5),
              Duration.ofHours(1),
              Duration.ofHours(24),
              100,
              List.of("yukta.")));

  private final RunAnalytics analytics = new RunAnalytics(new ExecutionEventFeed());

  private final MetricsProvider provider = new MetricsProvider(sampler, analytics);

  private void finishedRun() {
    final Instant now = Instant.now();
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged("e", "s1", "w1", "RUNNING", now.minusMillis(40)));
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("e", "s1", "w1", "SUCCESS", now));
  }

  @Test
  void globalIsTheDefaultAndMergesSampledMetersWithRunAggregates() {
    registry.gauge(GAUGE, 3);
    sampler.sample(System.currentTimeMillis());
    finishedRun();

    for (final String scope : new String[] {null, " ", "global"}) {
      StepVerifier.create(provider.getMetricsSnapshot(scope))
          .assertNext(
              s -> {
                assertThat(s.scope()).isEqualTo("global");
                assertThat(s.values())
                    .containsEntry(GAUGE, 3.0)
                    .containsEntry("runs.finished", 1.0);
              })
          .verifyComplete();
    }
  }

  @Test
  void scopeHasOnlyItsRunAggregatesAndUnknownScopeNone() {
    registry.gauge(GAUGE, 3);
    sampler.sample(System.currentTimeMillis());
    finishedRun();

    StepVerifier.create(provider.getMetricsSnapshot("workflow:w1"))
        .assertNext(
            s -> assertThat(s.values()).containsEntry("runs.SUCCESS", 1.0).doesNotContainKey(GAUGE))
        .verifyComplete();
    StepVerifier.create(provider.getMetricsSnapshot("session:nope"))
        .assertNext(s -> assertThat(s.values()).isEmpty())
        .verifyComplete();
  }
}
