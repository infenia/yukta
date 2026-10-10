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
    StepVerifier.create(trigger.start(Map.of(INTERVAL_MS, 10, "maxRuns", 2)))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("tick")).isEqualTo(1L))
        .assertNext(m -> assertThat(((Map<?, ?>) m.payload()).get("tick")).isEqualTo(2L))
        .verifyComplete();
  }

  @Test
  void start_cron_firesEverySecond() {
    StepVerifier.create(trigger.start(Map.of(CRON, "* * * * * *", "maxRuns", 1)))
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
  void validateConfig_acceptsValid() {
    StepVerifier.create(trigger.validateConfig(Map.of(INTERVAL_MS, 100))).verifyComplete();
    StepVerifier.create(trigger.validateConfig(Map.of(CRON, "0 0 * * * *", "zone", "UTC")))
        .verifyComplete();
  }
}
