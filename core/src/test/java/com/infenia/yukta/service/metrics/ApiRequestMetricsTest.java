// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.metrics.ApiRequestMetrics.Operation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for ApiRequestMetrics. */
@NoArgsConstructor
class ApiRequestMetricsTest {

  @ParameterizedTest
  @CsvSource({
    "GET,/api/sessions,READ",
    "HEAD,/api/sessions/s,READ",
    "POST,/api/sessions/workflow/start,RUN",
    "POST,/api/sessions/s/executions/e/restart,RUN",
    "POST,/api/sessions/s/executions/e/restart/n,RUN",
    "POST,/api/sessions/s/test-runs,RUN",
    "POST,/hooks/s/p,RUN",
    "POST,/api/sessions,CHANGE",
    "PUT,/api/sessions/s,CHANGE",
    "PATCH,/api/sessions/s,CHANGE",
    "DELETE,/api/sessions/s,CHANGE",
    "POST,/api/executions/e/pause,CHANGE",
    "OPTIONS,/api/sessions,OTHER"
  })
  void classifiesByMethodAndPath(final String method, final String path, final Operation expected) {
    assertThat(ApiRequestMetrics.classify(method, path)).contains(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "GET,/",
    "GET,/assets/app.js",
    "GET,/actuator/health",
    "GET,/api/metrics/snapshot",
    "GET,/api/metrics",
    "POST,/mcp"
  })
  void doesNotCountWhatIsNotTheApiOrIsTheMetricsEndpoints(final String method, final String path) {
    assertThat(ApiRequestMetrics.classify(method, path)).isEqualTo(Optional.empty());
  }

  @Test
  void recordsOneTimerPerOperation() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ApiRequestMetrics metrics = new ApiRequestMetrics(registry);

    metrics.record(Operation.READ, Duration.ofMillis(20));
    metrics.record(Operation.READ, Duration.ofMillis(40));
    metrics.record(Operation.RUN, Duration.ofMillis(5));

    assertThat(registry.get(ApiRequestMetrics.METER).tag("op", "read").timer().count())
        .isEqualTo(2);
    assertThat(
            registry
                .get(ApiRequestMetrics.METER)
                .tag("op", "read")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(60);
    assertThat(registry.get(ApiRequestMetrics.METER).tag("op", "run").timer().count()).isEqualTo(1);
    assertThat(registry.find(ApiRequestMetrics.METER).tag("op", "change").timer()).isNull();
  }
}
