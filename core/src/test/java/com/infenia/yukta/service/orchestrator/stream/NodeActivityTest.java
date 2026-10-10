// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class NodeActivityTest {

  private final VirtualTimeScheduler time = VirtualTimeScheduler.create();
  private final NodeActivity activity = new NodeActivity(time);
  private final Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();

  private StepVerifier.FirstStep<String> guarded(
      final Duration idle, final Duration deadline, final AtomicBoolean paused) {
    return StepVerifier.create(activity.guard(source.asFlux(), idle, deadline, time, paused::get));
  }

  private void run(final Duration total, final Duration step) {
    for (Duration spent = Duration.ZERO; spent.compareTo(total) < 0; spent = spent.plus(step)) {
      activity.touch(); // output or heartbeat: keeps the idle clock fresh
      time.advanceTimeBy(step);
    }
  }

  @Test
  void deadline_endsBusyNodeThatKeepsShowingActivity() {
    final var verifier =
        guarded(Duration.ofSeconds(1), Duration.ofSeconds(5), new AtomicBoolean())
            .expectErrorSatisfies(
                e ->
                    assertThat(e)
                        .isInstanceOf(TimeoutException.class)
                        .hasMessageContaining("deadline"))
            .verifyLater();

    run(Duration.ofSeconds(8), Duration.ofMillis(200));

    verifier.verify(Duration.ofSeconds(1));
  }

  @Test
  void withoutDeadline_busyNodeNeverTimesOut() {
    final var verifier =
        guarded(Duration.ofSeconds(1), null, new AtomicBoolean())
            .expectNext("a")
            .thenCancel()
            .verifyLater();

    run(Duration.ofSeconds(8), Duration.ofMillis(200));
    source.tryEmitNext("a");

    verifier.verify(Duration.ofSeconds(1));
  }

  @Test
  void deadline_doesNotCountPausedTime() {
    final AtomicBoolean paused = new AtomicBoolean();
    final var verifier =
        guarded(Duration.ofSeconds(20), Duration.ofSeconds(5), paused)
            .expectNext("a")
            .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("deadline"))
            .verifyLater();
    activity.touch();
    time.advanceTimeBy(Duration.ofSeconds(2));
    paused.set(true);
    time.advanceTimeBy(Duration.ofSeconds(30));
    source.tryEmitNext("a");
    paused.set(false);

    time.advanceTimeBy(Duration.ofSeconds(2));
    time.advanceTimeBy(Duration.ofSeconds(2));

    verifier.verify(Duration.ofSeconds(1));
  }

  @Test
  void deadline_startsAtTheFirstActivity_notAtSubscription() {
    final var verifier =
        guarded(Duration.ofSeconds(60), Duration.ofSeconds(5), new AtomicBoolean())
            .expectNext("late")
            .thenCancel()
            .verifyLater();
    time.advanceTimeBy(Duration.ofSeconds(30)); // waiting for upstream, no activity yet
    source.tryEmitNext("late");

    verifier.verify(Duration.ofSeconds(1));
  }

  @Test
  void retime_replacesTheIdleTimeoutOnceKnown() {
    final var verifier =
        guarded(Duration.ofSeconds(1), null, new AtomicBoolean())
            .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("idle for PT10S"))
            .verifyLater();
    activity.touch();
    activity.retime(Duration.ofSeconds(10));

    time.advanceTimeBy(Duration.ofSeconds(5)); // would have expired with the original 1 s
    time.advanceTimeBy(Duration.ofSeconds(6));

    verifier.verify(Duration.ofSeconds(1));
  }

  @Test
  void guard_withoutDeadline_stillPassesElementsAndCompletes() {
    StepVerifier.create(
            activity.guard(Flux.just("a", "b"), Duration.ofSeconds(1), time, () -> false))
        .expectNext("a", "b")
        .verifyComplete();
  }
}
