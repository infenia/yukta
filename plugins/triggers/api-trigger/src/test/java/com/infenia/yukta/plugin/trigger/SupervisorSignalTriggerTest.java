// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class SupervisorSignalTriggerTest {

  private final SupervisorSignalTrigger trigger = new SupervisorSignalTrigger();

  @Test
  void itEmitsTheSignalThatStartedTheRun() {
    assertThat(trigger.getType()).isEqualTo("SUPERVISOR_SIGNAL");
    assertThat(trigger.describe().description()).isNotBlank();
    assertThat(trigger.getConfigSchema()).hasSize(1);
    final Map<String, Object> signal = Map.of("executionId", "e1");

    StepVerifier.create(trigger.start(Map.of()).contextWrite(ctx -> ctx.put("payload", signal)))
        .assertNext(message -> assertThat(message.payload()).isEqualTo(signal))
        .verifyComplete();
    StepVerifier.create(trigger.start(Map.of()))
        .assertNext(message -> assertThat(message.payload()).isEqualTo(Map.of()))
        .verifyComplete();
  }

  @Test
  void signalKindsAreCheckedAgainstTheContract() {
    StepVerifier.create(trigger.validateConfig(Map.of())).verifyComplete();
    StepVerifier.create(trigger.validateConfig(Map.of("signals", " "))).verifyComplete();
    StepVerifier.create(trigger.validateConfig(Map.of("signals", "event, Tick,REVIEW")))
        .verifyComplete();
    StepVerifier.create(trigger.validateConfig(Map.of("signals", "event,whenever")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
