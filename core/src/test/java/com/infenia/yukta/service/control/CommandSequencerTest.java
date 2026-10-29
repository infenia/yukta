// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
class CommandSequencerTest {

  private final CommandSequencer sequencer = new CommandSequencer();

  private Mono<Void> job(final List<String> log, final String name, final Duration delay) {
    return Mono.delay(delay).doOnNext(_ -> log.add(name)).then();
  }

  @Test
  void sameKey_runsInSubmissionOrder_evenWhenAnEarlierJobIsSlower() {
    final List<String> log = new CopyOnWriteArrayList<>();
    final Mono<Void> first = sequencer.sequence("e1", job(log, "first", Duration.ofMillis(150)));
    final Mono<Void> second = sequencer.sequence("e1", job(log, "second", Duration.ZERO));

    // subscribing in this order fixes their place in the queue
    final var firstDone = first.toFuture();
    final var secondDone = second.toFuture();
    StepVerifier.create(Mono.fromFuture(secondDone).then(Mono.fromFuture(firstDone)))
        .verifyComplete();

    assertThat(log).containsExactly("first", "second");
  }

  @Test
  void differentKeys_doNotWaitForEachOther() {
    final List<String> log = new CopyOnWriteArrayList<>();
    final var slow = sequencer.sequence("e1", job(log, "slow", Duration.ofMillis(300))).toFuture();
    final var fast = sequencer.sequence("e2", job(log, "fast", Duration.ZERO)).toFuture();

    StepVerifier.create(Mono.fromFuture(fast).then(Mono.fromFuture(slow))).verifyComplete();

    assertThat(log).containsExactly("fast", "slow");
  }

  @Test
  void failingJob_doesNotBlockTheNextOne_andItsErrorIsReportedToItsCaller() {
    final List<String> log = new CopyOnWriteArrayList<>();
    final Mono<Void> failing =
        sequencer.sequence("e1", Mono.error(new IllegalStateException("no")));
    final Mono<Void> next = sequencer.sequence("e1", job(log, "next", Duration.ZERO));

    final var failed = failing.toFuture();
    final var nextDone = next.toFuture();

    StepVerifier.create(Mono.fromFuture(failed)).expectError(IllegalStateException.class).verify();
    StepVerifier.create(Mono.fromFuture(nextDone)).verifyComplete();
    assertThat(log).containsExactly("next");
  }

  @Test
  void cancelledJob_releasesTheQueue() {
    final List<String> log = new CopyOnWriteArrayList<>();
    final var never = sequencer.sequence("e1", Mono.never()).subscribe();
    final var waiting = sequencer.sequence("e1", job(log, "after", Duration.ZERO)).toFuture();

    never.dispose();

    StepVerifier.create(Mono.fromFuture(waiting)).verifyComplete();
    assertThat(log).containsExactly("after");
  }

  @Test
  void cancelledWaiter_doesNotLetLaterJobOvertakeTheRunningOne() {
    final List<String> log = new CopyOnWriteArrayList<>();
    final var running = sequencer.sequence("e1", Mono.never()).subscribe();
    final var waiter = sequencer.sequence("e1", job(log, "b", Duration.ZERO)).subscribe();
    final var last = sequencer.sequence("e1", job(log, "c", Duration.ZERO)).toFuture();

    waiter.dispose();

    assertThat(last).isNotDone();
    assertThat(log).isEmpty();
    running.dispose();
    StepVerifier.create(Mono.fromFuture(last)).verifyComplete();
    assertThat(log).containsExactly("c");
  }

  @Test
  void bookkeepingDisappearsOnceTheLastJobIsDone() {
    StepVerifier.create(sequencer.sequence("e1", Mono.empty())).verifyComplete();
    StepVerifier.create(sequencer.sequence("e2", Mono.error(new IllegalStateException())))
        .expectError()
        .verify();

    assertThat(sequencer.pendingKeys()).isZero();
  }
}
