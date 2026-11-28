// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.metrics.ApiRequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Tests for ApiRequestMetricsFilter. */
@NoArgsConstructor
class ApiRequestMetricsFilterTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final ApiRequestMetricsFilter filter =
      new ApiRequestMetricsFilter(new ApiRequestMetrics(registry));

  private void run(final MockServerHttpRequest.BaseBuilder<?> request) {
    final MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> e.getResponse().setComplete()).block();
  }

  private long count(final String op) {
    final var timer = registry.find(ApiRequestMetrics.METER).tag("op", op).timer();
    return timer == null ? 0 : timer.count();
  }

  @Test
  void timesCallsByWhatTheyDo() {
    run(MockServerHttpRequest.get("/api/sessions"));
    run(MockServerHttpRequest.get("/api/sessions/s"));
    run(MockServerHttpRequest.post("/api/sessions/workflow/start"));
    run(MockServerHttpRequest.delete("/api/sessions/s"));

    assertThat(count("read")).isEqualTo(2);
    assertThat(count("run")).isEqualTo(1);
    assertThat(count("change")).isEqualTo(1);
  }

  @Test
  void leavesOutTheMetricsEndpointsThePagesAndEventStreams() {
    run(MockServerHttpRequest.get("/api/metrics/snapshot"));
    run(MockServerHttpRequest.get("/assets/app.js"));
    run(MockServerHttpRequest.get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM));

    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  void stillTimesCallsThatFail() {
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/sessions"));

    filter
        .filter(exchange, _ -> Mono.error(new IllegalStateException("x")))
        .onErrorResume(_ -> Mono.empty())
        .block();

    assertThat(count("read")).isEqualTo(1);
  }
}
