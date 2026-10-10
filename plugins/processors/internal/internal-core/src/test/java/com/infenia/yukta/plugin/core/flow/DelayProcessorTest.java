// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for DelayProcessor. */
class DelayProcessorTest {

  /** The processor under test. */
  private final DelayProcessor processor = new DelayProcessor();

  @Test
  void process_delaysAndKeepsOrder() {
    final Message<?> first = DefaultMessage.create(UUID.randomUUID(), Map.of("n", 1));
    final Message<?> second = DefaultMessage.create(UUID.randomUUID(), Map.of("n", 2));
    final long start = System.nanoTime();
    StepVerifier.create(processor.process(Flux.just(first, second), Map.of("delayMs", 50)))
        .expectNext(first, second)
        .verifyComplete();
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .isGreaterThanOrEqualTo(Duration.ofMillis(90));
  }

  @Test
  void typeAndDescription() {
    assertThat(processor.getType()).isEqualTo("DELAY");
    assertThat(processor.describe().usagePattern()).contains("delayMs");
  }

  @Test
  void validateConfig_requiresPositiveDelay() {
    StepVerifier.create(processor.validateConfig(Map.of("delayMs", 5))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("delayMs", 0)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
