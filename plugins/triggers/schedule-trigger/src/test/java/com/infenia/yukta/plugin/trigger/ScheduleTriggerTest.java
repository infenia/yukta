// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for ScheduleTrigger. */
@NoArgsConstructor
class ScheduleTriggerTest {

  /** Test literal maxRuns. */
  private static final String MAX_RUNS = "maxRuns";

  /** Config key. */
  private static final String INTERVAL_MS = "intervalMs";

  /** Config key. */
  private static final String CRON = "cron";

  /** The trigger instance under test. */
  private final ScheduleTrigger trigger = new ScheduleTrigger();

  @Test
  void type_returnsScheduleTrigger() {
    assertThat(trigger.getType()).isEqualTo("SCHEDULE_TRIGGER");
    assertThat(trigger.describe().description()).isNotBlank();
  }

  @Test
  void start_interval_emitsTicksUpToMaxRuns() {
    StepVerifier.create(trigger.start(Map.of(INTERVAL_MS, 10, MAX_RUNS, 2)))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("tick")).isEqualTo(1L))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("tick")).isEqualTo(2L))
        .verifyComplete();
  }

  @Test
  void start_cron_firesEverySecond() {
    StepVerifier.create(trigger.start(Map.of(CRON, "* * * * * *", MAX_RUNS, 1)))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("firedAt")).isNotNull())
        .expectComplete()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void validateConfig_rejectsMissingBothOrInvalid() {
    StepVerifier.create(trigger.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(trigger.validateConfig(Map.of(CRON, "* * * * * *", INTERVAL_MS, 5)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(trigger.validateConfig(Map.of(INTERVAL_MS, 0)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(trigger.validateConfig(Map.of(CRON, "not a cron")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validateConfig_rejectsBadMaxRunsAndExhaustedCron() {
    for (final Object bad : java.util.List.of(0, -1, 0.5, "x")) {
      StepVerifier.create(trigger.validateConfig(Map.of(INTERVAL_MS, 100, MAX_RUNS, bad)))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
    StepVerifier.create(
            trigger.validateConfig(Map.of(CRON, "0 0 0 1 1 *", "zone", "UTC", MAX_RUNS, 1)))
        .verifyComplete();
    // 31 February never happens
    StepVerifier.create(trigger.validateConfig(Map.of(CRON, "0 0 0 31 2 *")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validateConfig_acceptsValid() {
    StepVerifier.create(trigger.validateConfig(Map.of(INTERVAL_MS, 100))).verifyComplete();
    StepVerifier.create(trigger.validateConfig(Map.of(CRON, "0 0 * * * *", "zone", "UTC")))
        .verifyComplete();
  }

  @Test
  void describe_triggerProvidesNodeDesign() {
    assertThat(trigger.describe().uiDesign())
        .hasValueSatisfying(
            design -> {
              assertThat(design.label()).isNotBlank();
              assertThat(design.width()).isEqualTo(140);
              assertThat(design.height()).isEqualTo(80);
            });
  }

  @Test
  void start_withoutMaxRuns_keepsEmitting() {
    StepVerifier.create(trigger.start(Map.of(INTERVAL_MS, 10)).take(2))
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  void start_interval_slowerDownstreamSkipsTicksInsteadOfEndingTheSchedule() {
    // One tick is requested and the next ones come with no demand: they used to end the schedule
    // with "Could not emit tick N due to lack of requests".
    StepVerifier.create(trigger.start(Map.of(INTERVAL_MS, 20)), 1)
        .expectNextCount(1)
        .thenAwait(Duration.ofMillis(300))
        .thenRequest(1)
        .expectNextCount(1)
        .thenCancel()
        .verify();
  }

  @Test
  void repeatWhileScheduled_endsOnceThereIsNoNextExecution() {
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.time.ZoneId zone = java.time.ZoneId.of("UTC");

    StepVerifier.create(
            ScheduleTrigger.repeatWhileScheduled(
                now -> calls.getAndIncrement() < 3 ? now.plus(Duration.ofMillis(10)) : null, zone))
        .expectNextCount(2)
        .verifyComplete();
  }
}
