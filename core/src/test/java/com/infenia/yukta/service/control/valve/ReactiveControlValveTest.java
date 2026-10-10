// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.valve;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.LambdaCanBeMethodReference",
  "PMD.LawOfDemeter"
})
class ReactiveControlValveTest {

  private ReactiveControlValve valve;

  @BeforeEach
  void setUp() {
    valve = new ReactiveControlValve();
  }

  @Test
  void testAllowPassageWhenFlowing() {
    StepVerifier.create(valve.allowPassage()).expectNext(true).verifyComplete();
  }

  @Test
  void testAllowPassageWhenPaused() {
    valve.pause();

    StepVerifier.create(valve.allowPassage().timeout(Duration.ofMillis(100)))
        .expectError()
        .verify();
  }

  @Test
  void testAllowPassageBlocksUntilResumed() {
    valve.pause();

    StepVerifier.create(
            valve
                .allowPassage()
                .doOnNext(
                    v -> {
                      assertThat(v).isTrue();
                    })
                .timeout(Duration.ofSeconds(1)))
        .then(() -> valve.resume())
        .expectNext(true)
        .verifyComplete();
  }

  @Test
  void testPauseAndResume() {
    assertThat(valve.isPaused()).isFalse();

    valve.pause();
    assertThat(valve.isPaused()).isTrue();

    valve.resume();
    assertThat(valve.isPaused()).isFalse();
  }

  @Test
  void testMultiplePauseResumeCycles() {
    for (int i = 0; i < 3; i++) {
      assertThat(valve.isPaused()).isFalse();
      valve.pause();
      assertThat(valve.isPaused()).isTrue();
      valve.resume();
    }
    assertThat(valve.isPaused()).isFalse();
  }

  @Test
  void testConcurrentPauseResume() throws InterruptedException {
    final int numThreads = 5;
    final Thread[] threads = new Thread[numThreads];

    for (int t = 0; t < numThreads; t++) {
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < 10; i++) {
                  if (i % 2 == 0) {
                    valve.pause();
                  } else {
                    valve.resume();
                  }
                }
              });
      threads[t].start();
    }

    for (final Thread thread : threads) {
      thread.join();
    }

    assertThat(valve.isPaused()).isFalse();
  }

  @Test
  void testAllowPassageNotBlockedWhenResumedBeforeCheck() {
    valve.pause();
    valve.resume();

    StepVerifier.create(valve.allowPassage()).expectNext(true).verifyComplete();
  }

  @Test
  void testEnableStepMode() {
    assertThat(valve.isStepMode()).isFalse();

    valve.enableStepMode();

    assertThat(valve.isStepMode()).isTrue();
    assertThat(valve.isPaused()).isTrue();
  }

  @Test
  void testDisableStepMode() {
    valve.enableStepMode();
    assertThat(valve.isStepMode()).isTrue();

    valve.disableStepMode();

    assertThat(valve.isStepMode()).isFalse();
    assertThat(valve.isPaused()).isFalse();
  }

  @Test
  void testStepWithoutStepMode() {
    assertThat(valve.step()).isFalse();
  }

  @Test
  void testStepWithStepMode() {
    valve.enableStepMode();

    final boolean stepped = valve.step();

    assertThat(stepped).isTrue();
  }

  @Test
  void testStepModeAllowsOneElementThenBlocks() {
    valve.enableStepMode();

    final Flux<Integer> source = Flux.range(1, 3).delayUntil(t -> valve.allowPassage().then());

    StepVerifier.create(source)
        .expectSubscription()
        .then(() -> assertThat(valve.step()).isTrue())
        .expectNext(1)
        .then(
            () -> {
              final var result =
                  valve.allowPassage().timeout(Duration.ofMillis(50)).onErrorReturn(false).block();
              assertThat(result).isFalse();
            })
        .then(() -> assertThat(valve.step()).isTrue())
        .expectNext(2)
        .then(() -> assertThat(valve.step()).isTrue())
        .expectNext(3)
        .verifyComplete();
  }

  @Test
  void testDisableStepModeReturnsToNormal() {
    valve.enableStepMode();
    valve.step();

    valve.disableStepMode();

    StepVerifier.create(valve.allowPassage()).expectNext(true).verifyComplete();
  }

  @Test
  void testBreakpointHoldsMatchingElementUntilResumed() {
    final Message<?> plain = DefaultMessage.create(UUID.randomUUID(), "ok");
    final Message<?> stop = DefaultMessage.create(UUID.randomUUID(), "stop-here");
    valve.setBreakpoint(m -> "stop-here".equals(m.payload()));

    StepVerifier.create(valve.allowPassage(plain)).expectNext(true).verifyComplete();
    StepVerifier.create(valve.allowPassage(stop))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(100))
        .then(
            () -> {
              assertThat(valve.isPaused()).isTrue();
              valve.resume();
            })
        .expectNext(true)
        .verifyComplete();

    valve.setBreakpoint(null);
    StepVerifier.create(valve.allowPassage(stop)).expectNext(true).verifyComplete();
  }
}
