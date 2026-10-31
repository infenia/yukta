// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.metrics.MetricsProperties;
import com.infenia.yukta.service.metrics.MetricsSampler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link MetricsController}. */
@NoArgsConstructor
class MetricsControllerTest {

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

  private final MetricsController controller = new MetricsController(sampler);

  @Test
  void answersEmptyBeforeTheFirstSample() {
    StepVerifier.create(controller.snapshot())
        .assertNext(r -> assertThat(r.data().values()).isEmpty())
        .verifyComplete();
    StepVerifier.create(controller.catalog())
        .assertNext(r -> assertThat(r.data().series()).isEmpty())
        .verifyComplete();
  }

  @Test
  void seriesReturnsSampledPoints() {
    registry.gauge("yukta.g", 7);
    sampler.sample(System.currentTimeMillis());

    StepVerifier.create(controller.series(List.of("yukta.g"), "15m"))
        .assertNext(s -> assertThat(s.data().series().get("yukta.g")).hasSize(1))
        .verifyComplete();
    StepVerifier.create(controller.snapshot())
        .assertNext(s -> assertThat(s.data().values()).containsEntry("yukta.g", 7.0))
        .verifyComplete();
  }

  @Test
  void unknownRangeIsABadRequest() {
    StepVerifier.create(controller.series(List.of("yukta.g"), "2d"))
        .expectErrorSatisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST))
        .verify();
  }

  @Test
  void streamEmitsTheNewestSampleFirst() {
    registry.gauge("yukta.g", 1);
    sampler.sample(1L);

    StepVerifier.create(controller.stream(null).take(1))
        .assertNext(e -> assertThat(e.data().time()).isEqualTo(1L))
        .verifyComplete();
  }
}
