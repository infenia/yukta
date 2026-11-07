// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@NoArgsConstructor
@ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.compiler.ExecutionResources - ")
class ExecutionResourcesTest {

  private static final Duration A_MINUTE = Duration.ofSeconds(60);

  private Mono<Void> run(
      final Duration timeout,
      final List<Mono<Void>> terminals,
      final List<Runnable> connectors,
      final List<Disposable> disposables) {
    return ExecutionResources.run(
        timeout, Schedulers.boundedElastic(), terminals, connectors, disposables, () -> false);
  }

  @Test
  void runsConnectorsInReverseOrderOnSubscription() {
    final List<Integer> order = new ArrayList<>();
    final List<Runnable> connectors = List.of(() -> order.add(0), () -> order.add(1));

    final Mono<Void> execution =
        run(A_MINUTE, List.of(Mono.empty()), connectors, new ArrayList<>());
    assertThat(order).isEmpty();
    StepVerifier.create(execution).verifyComplete();

    assertThat(order).containsExactly(1, 0);
  }

  @Test
  void disposesSubscriptionsRegisteredByConnectorsWhileRunning() {
    final Disposable late = mock(Disposable.class);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = List.of(() -> disposables.add(late));

    StepVerifier.create(run(A_MINUTE, List.of(Mono.empty()), connectors, disposables))
        .verifyComplete();

    verify(late).dispose();
  }

  @Test
  void failingConnectorFailsTheRun_andThrowingDisposableDoesNotStopTheRest() {
    final Disposable bad = mock(Disposable.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("x")).when(bad).dispose();
    final Disposable good = mock(Disposable.class);
    final List<Runnable> connectors =
        List.of(
            () -> {
              throw new IllegalStateException("connector failed");
            });

    StepVerifier.create(
            run(A_MINUTE, List.of(Mono.empty()), connectors, new ArrayList<>(List.of(bad, good))))
        .expectErrorMessage("connector failed")
        .verify();

    verify(good).dispose();
  }

  @Test
  void failedTerminal_failsTheRunAtOnce_andCancelsTheOthers() {
    final AtomicBoolean siblingCancelled = new AtomicBoolean();
    final Mono<Void> stalled = Mono.<Void>never().doOnCancel(() -> siblingCancelled.set(true));
    final Mono<Void> failing =
        Mono.delay(Duration.ofMillis(50))
            .then(Mono.error(new IllegalStateException("terminal failed")));

    StepVerifier.create(
            run(A_MINUTE, List.of(stalled, failing), new ArrayList<>(), new ArrayList<>()))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(2));

    assertThat(siblingCancelled).isTrue();
  }

  @Test
  void timesOutWithTheRunTimeoutAndStillDisposes() {
    final Disposable resource = mock(Disposable.class);

    StepVerifier.create(
            run(
                Duration.ofSeconds(1),
                List.of(Mono.never()),
                List.of(),
                new ArrayList<>(List.of(resource))))
        .expectError(ExecutionTimeoutException.class)
        .verify(Duration.ofSeconds(5));

    verify(resource).dispose();
  }

  @Test
  void runDeadline_doesNotFireWhilePaused_butFiresAfterResume() {
    final AtomicBoolean paused = new AtomicBoolean(true);

    StepVerifier.withVirtualTime(
            () ->
                ExecutionResources.run(
                    A_MINUTE,
                    Schedulers.parallel(),
                    List.of(Mono.never()),
                    List.of(),
                    new ArrayList<>(),
                    paused::get))
        .expectSubscription()
        .expectNoEvent(Duration.ofMinutes(10))
        .then(() -> paused.set(false))
        .thenAwait(Duration.ofSeconds(60))
        .expectError(TimeoutException.class)
        .verify();
  }
}
