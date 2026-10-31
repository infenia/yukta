// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.TooManyMethods")
class MetricsSamplerTest {

  private static final long T0 = 1_000_000L;

  private static final long END = T0 + 99 * 5_000L;

  private static final String COUNTER_A = "yukta.a";

  private static final String GAUGE = "yukta.g";

  private static final List<String> ALLOW = List.of("yukta.");

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private static Map<String, Double> values(final MetricsSampler sampler) {
    return Objects.requireNonNull(sampler.snapshot().block()).values();
  }

  private MetricsSampler sampler(final int maxSeries) {
    return new MetricsSampler(
        registry,
        new MetricsProperties(
            true,
            Duration.ofSeconds(5),
            Duration.ofMinutes(1),
            Duration.ofMinutes(3),
            maxSeries,
            ALLOW));
  }

  @Test
  void sampleReadsGaugesCountersAndTimers() {
    final AtomicInteger running = new AtomicInteger(3);
    Gauge.builder("yukta.executions.running", running, AtomicInteger::get).register(registry);
    final Counter counter = registry.counter("yukta.thing", "reason", "full");
    final Timer timer = registry.timer("yukta.execution.duration", "status", "SUCCESS");
    registry.counter("other.ignored").increment();
    final MetricsSampler sampler = sampler(100);

    sampler.sample(T0);
    counter.increment(10);
    timer.record(Duration.ofMillis(200));
    timer.record(Duration.ofMillis(400));
    sampler.sample(T0 + 5_000);

    final var values = values(sampler);
    assertThat(values)
        .containsEntry("yukta.executions.running", 3.0)
        .containsEntry("yukta.thing{reason=full}|rate", 2.0)
        .containsEntry("yukta.execution.duration{status=SUCCESS}|rate", 0.4)
        .containsEntry("yukta.execution.duration{status=SUCCESS}|mean", 300.0)
        .containsEntry("yukta.execution.duration{status=SUCCESS}|max", 400.0)
        .doesNotContainKey("other.ignored|rate");
  }

  @Test
  void counterResetAndRemovedMeterDoNotBreakSampling() {
    final Counter counter = registry.counter(COUNTER_A);
    final MetricsSampler sampler = sampler(100);
    counter.increment(5);
    sampler.sample(T0);
    registry.remove(counter);
    sampler.sample(T0 + 5_000);
    registry.counter(COUNTER_A).increment(1);
    sampler.sample(T0 + 10_000);

    assertThat(values(sampler)).containsEntry("yukta.a|rate", 0.0);
  }

  @Test
  void seriesCapBoundsTheSample() {
    for (int i = 0; i < 20; i++) {
      registry.counter("yukta.c", "i", String.valueOf(i));
    }
    final MetricsSampler sampler = sampler(5);
    sampler.sample(T0);

    assertThat(values(sampler)).hasSize(5);
  }

  @Test
  void buffersAreBoundedAndSeriesPicksTheRightOne() {
    final AtomicInteger v = new AtomicInteger();
    Gauge.builder(GAUGE, v, AtomicInteger::get).register(registry);
    final MetricsSampler sampler = sampler(10);
    // fine holds 12 samples (1 min / 5 s), coarse 3 (3 min / 1 min)
    for (int i = 0; i < 100; i++) {
      v.set(i);
      sampler.sample(T0 + i * 5_000L);
    }

    assertThat(sampler.series(List.of(GAUGE), Duration.ofMinutes(1), END).get(GAUGE)).hasSize(12);
    assertThat(sampler.series(List.of(GAUGE), Duration.ofHours(6), END).get(GAUGE))
        .hasSizeLessThanOrEqualTo(3);
    assertThat(sampler.catalog()).containsExactly(GAUGE);
  }

  @Test
  void streamStartsWithTheNewestSample() {
    Gauge.builder(GAUGE, () -> 1).register(registry);
    final MetricsSampler sampler = sampler(10);
    sampler.sample(T0);

    StepVerifier.create(sampler.stream().take(2))
        .assertNext(s -> assertThat(s.time()).isEqualTo(T0))
        .then(() -> sampler.sample(T0 + 5_000))
        .assertNext(s -> assertThat(s.time()).isEqualTo(T0 + 5_000))
        .verifyComplete();
  }

  @Test
  void percentilesComeFromTheConfiguredTimers() {
    final SimpleMeterRegistry withFilter = new SimpleMeterRegistry();
    withFilter.config().meterFilter(new MetricsConfiguration().yuktaPercentiles());
    withFilter
        .timer("yukta.execution.duration", "status", "SUCCESS")
        .record(Duration.ofMillis(100));
    final MetricsSampler sampler =
        new MetricsSampler(
            withFilter,
            new MetricsProperties(
                true,
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                Duration.ofHours(24),
                100,
                ALLOW));
    sampler.sample(T0);

    assertThat(values(sampler))
        .containsKeys(
            "yukta.execution.duration{status=SUCCESS}|p50",
            "yukta.execution.duration{status=SUCCESS}|p95",
            "yukta.execution.duration{status=SUCCESS}|p99");
  }

  @Test
  void otherMeterTypesAreNotCharted() {
    registry.summary("yukta.size").record(3);
    final MetricsSampler sampler = sampler(10);
    assertThat(sampler.catalog()).isEmpty();
    sampler.sample(T0);

    assertThat(values(sampler)).isEmpty();
  }

  @Test
  void functionCountersBecomeRates() {
    final AtomicInteger total = new AtomicInteger();
    FunctionCounter.builder("yukta.fc", total, AtomicInteger::get).register(registry);
    final MetricsSampler sampler = sampler(10);
    sampler.sample(T0);
    total.set(10);
    sampler.sample(T0 + 5_000);

    assertThat(values(sampler)).containsEntry("yukta.fc|rate", 2.0);
  }

  @Test
  void unreadableMeterIsSkipped() {
    final Gauge broken = mock(Gauge.class);
    when(broken.getId())
        .thenReturn(new Meter.Id("yukta.broken", Tags.empty(), null, null, Meter.Type.GAUGE));
    when(broken.value()).thenThrow(new IllegalStateException("gone"));
    final MeterRegistry mocked = mock(MeterRegistry.class);
    when(mocked.getMeters()).thenReturn(List.of(broken));
    final MetricsSampler sampler =
        new MetricsSampler(
            mocked,
            new MetricsProperties(
                true, Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(24), 10, ALLOW));
    sampler.sample(T0);

    assertThat(values(sampler)).isEmpty();
  }

  @Test
  void startSamplesOnTheIntervalUntilStopped() {
    Gauge.builder(GAUGE, () -> 1).register(registry);
    final MetricsSampler sampler =
        new MetricsSampler(
            registry,
            new MetricsProperties(
                true,
                Duration.ofMillis(10),
                Duration.ofMinutes(1),
                Duration.ofMinutes(3),
                10,
                ALLOW));
    sampler.start();
    try {
      await().atMost(3, TimeUnit.SECONDS).until(() -> sampler.snapshot().block() != null);
      assertThat(values(sampler)).containsKey(GAUGE);
    } finally {
      sampler.stop();
    }
  }

  @Test
  void disabledSamplerNeverStarts() {
    final MetricsSampler sampler =
        new MetricsSampler(
            registry,
            new MetricsProperties(
                false,
                Duration.ofMillis(10),
                Duration.ofMinutes(1),
                Duration.ofMinutes(3),
                10,
                ALLOW));
    sampler.start();
    sampler.stop();

    assertThat(sampler.snapshot().block()).isNull();
  }

  @Test
  void anEmptyAllowListFallsBackToTheDefaults() {
    final MetricsProperties properties =
        new MetricsProperties(
            true, Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(24), 10, List.of());

    assertThat(properties.allowList()).contains("yukta.", "llm.");
  }

  @Test
  void onlyTheKnownPercentilesAreKept() {
    Timer.builder("yukta.t")
        .publishPercentiles(0.5, 0.9)
        .register(registry)
        .record(Duration.ofMillis(5));
    final MetricsSampler sampler = sampler(10);
    sampler.sample(T0);

    assertThat(values(sampler)).containsKey("yukta.t|p50").doesNotContainKey("yukta.t|p90");
  }

  @Test
  void otherTimersGetNoPercentilesFromTheFilter() {
    final SimpleMeterRegistry withFilter = new SimpleMeterRegistry();
    withFilter.config().meterFilter(new MetricsConfiguration().yuktaPercentiles());
    withFilter.timer("yukta.other.timer").record(Duration.ofMillis(5));
    final MetricsSampler sampler =
        new MetricsSampler(
            withFilter,
            new MetricsProperties(
                true,
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                Duration.ofHours(24),
                100,
                ALLOW));
    sampler.sample(T0);

    assertThat(values(sampler)).doesNotContainKey("yukta.other.timer|p95");
  }

  @Test
  void configuredAllowListWinsAndNullMeansDefault() {
    final Duration d = Duration.ofSeconds(5);

    assertThat(new MetricsProperties(true, d, d, d, 10, List.of("x.")).allowList())
        .containsExactly("x.");
    assertThat(new MetricsProperties(true, d, d, d, 10, null).allowList()).contains("yukta.");
  }

  @Test
  void nonFiniteValuesBecomeZero() {
    Gauge.builder("yukta.nan", () -> Double.NaN).register(registry);
    final MetricsSampler sampler = sampler(10);
    sampler.sample(T0);

    assertThat(values(sampler)).containsEntry("yukta.nan", 0.0);
  }

  @Test
  void seriesLeavesOutOldSamplesAndOtherNames() {
    Gauge.builder(COUNTER_A, () -> 1).register(registry);
    Gauge.builder("yukta.b", () -> 2).register(registry);
    final MetricsSampler sampler = sampler(10);
    sampler.sample(T0);
    sampler.sample(T0 + 5_000);
    sampler.sample(T0 + 10_000);

    final var series = sampler.series(List.of(COUNTER_A), Duration.ofSeconds(6), T0 + 10_000);

    assertThat(series).containsOnlyKeys(COUNTER_A);
    assertThat(series.get(COUNTER_A)).hasSize(2);
  }
}
