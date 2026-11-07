// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

@NoArgsConstructor
class LeaderTimerTest {

  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final AtomicInteger runs = new AtomicInteger();

  @Test
  void jobSlowerThanManyTicksDoesNotEndTheTimer() {
    // one round takes 100 ticks: a queue of ticks would overflow and end the timer
    final LeaderTimer timer =
        new LeaderTimer(
            () -> true,
            Duration.ofSeconds(1),
            scheduler,
            () ->
                Mono.fromRunnable(runs::incrementAndGet)
                    .delaySubscription(Duration.ofSeconds(100), scheduler)
                    .then());
    timer.start();

    scheduler.advanceTimeBy(Duration.ofSeconds(350));

    assertThat(runs).hasValueGreaterThanOrEqualTo(3);
    timer.stop();
    assertThat(timer.isRunning()).isFalse();
  }

  @Test
  void failingJobIsRunAgainAtTheNextTick() {
    final LeaderTimer timer =
        new LeaderTimer(
            () -> true,
            Duration.ofSeconds(1),
            scheduler,
            () -> {
              runs.incrementAndGet();
              return Mono.error(new IllegalStateException("store down"));
            });
    timer.start();
    timer.start();

    scheduler.advanceTimeBy(Duration.ofSeconds(3));

    assertThat(runs).hasValue(3);
    timer.stop();
  }

  @Test
  void standbyServerRunsNothing() {
    final LeaderTimer timer =
        new LeaderTimer(
            () -> false,
            Duration.ofSeconds(1),
            scheduler,
            () -> {
              runs.incrementAndGet();
              return Mono.empty();
            });
    timer.stop();
    timer.start();

    scheduler.advanceTimeBy(Duration.ofSeconds(2));

    assertThat(timer.isRunning()).isTrue();
    assertThat(runs).hasValue(0);
    timer.stop();
  }
}
