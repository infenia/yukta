// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.valve;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
  "PMD.AvoidLiteralsInIfCondition",
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
  void testAsTransformFlowing() {
    StepVerifier.create(Flux.range(1, 5).transform(valve.asTransform()))
        .expectNext(1, 2, 3, 4, 5)
        .verifyComplete();
  }

  @Test
  void testAsTransformPaused() {
    valve.pause();

    StepVerifier.create(
            Flux.range(1, 5).transform(valve.asTransform()).timeout(Duration.ofMillis(100)))
        .expectError()
        .verify();
  }

  @Test
  void testAsTransformPausedThenResumed() {
    valve.pause();

    final Flux<Integer> flux =
        Flux.range(1, 5)
            .transform(valve.asTransform())
            .doOnNext(
                v -> {
                  if (v == 2) {
                    valve.resume();
                  }
                });

    StepVerifier.create(flux).then(() -> valve.resume()).expectNext(1, 2, 3, 4, 5).verifyComplete();
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

    final Flux<Integer> source = Flux.range(1, 3).transform(valve.asTransform());

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
}
