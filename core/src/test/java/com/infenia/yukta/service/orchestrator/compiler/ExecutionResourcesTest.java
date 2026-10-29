// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.session.store.SessionConfigStore;
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
@SuppressWarnings("PMD.TooManyStaticImports")
@ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.compiler.ExecutionResources - ")
class ExecutionResourcesTest {

  private final SessionConfigStore configStore = mock(SessionConfigStore.class);

  private Mono<Void> run(
      final String sessionId,
      final List<Mono<Void>> terminals,
      final List<Runnable> connectors,
      final List<Disposable> disposables) {
    return ExecutionResources.run(
        configStore,
        Schedulers.boundedElastic(),
        sessionId,
        terminals,
        connectors,
        disposables,
        () -> false);
  }

  @Test
  void runsConnectorsInReverseOrderOnSubscription() {
    when(configStore.getExecutionTimeout("s")).thenReturn(Mono.just(60L));
    final List<Integer> order = new ArrayList<>();
    final List<Runnable> connectors = List.of(() -> order.add(0), () -> order.add(1));

    final Mono<Void> execution = run("s", List.of(Mono.empty()), connectors, new ArrayList<>());
    assertThat(order).isEmpty();
    StepVerifier.create(execution).verifyComplete();

    assertThat(order).containsExactly(1, 0);
  }

  @Test
  void disposesSubscriptionsRegisteredByConnectorsWhileRunning() {
    when(configStore.getExecutionTimeout("s")).thenReturn(Mono.just(60L));
    final Disposable late = mock(Disposable.class);
    final List<Disposable> disposables = new ArrayList<>();
    final List<Runnable> connectors = List.of(() -> disposables.add(late));

    StepVerifier.create(run("s", List.of(Mono.empty()), connectors, disposables)).verifyComplete();

    verify(late).dispose();
  }

  @Test
  void throwingConnectorOrDisposableDoesNotStopTheRest() {
    when(configStore.getExecutionTimeout("s")).thenReturn(Mono.just(60L));
    final Disposable bad = mock(Disposable.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("x")).when(bad).dispose();
    final Disposable good = mock(Disposable.class);
    final List<Runnable> connectors =
        List.of(
            () -> {
              throw new IllegalStateException("connector failed");
            });

    StepVerifier.create(
            run("s", List.of(Mono.empty()), connectors, new ArrayList<>(List.of(bad, good))))
        .verifyComplete();

    verify(good).dispose();
  }

  @Test
  void failedTerminal_failsTheRunAtOnce_andCancelsTheOthers() {
    when(configStore.getExecutionTimeout("s")).thenReturn(Mono.just(60L));
    final AtomicBoolean siblingCancelled = new AtomicBoolean();
    final Mono<Void> stalled = Mono.<Void>never().doOnCancel(() -> siblingCancelled.set(true));
    final Mono<Void> failing =
        Mono.delay(Duration.ofMillis(50))
            .then(Mono.error(new IllegalStateException("terminal failed")));

    StepVerifier.create(run("s", List.of(stalled, failing), new ArrayList<>(), new ArrayList<>()))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(2));

    assertThat(siblingCancelled).isTrue();
  }

  @Test
  void timesOutWithTheSessionTimeoutAndStillDisposes() {
    when(configStore.getExecutionTimeout("s")).thenReturn(Mono.just(1L));
    final Disposable resource = mock(Disposable.class);

    StepVerifier.create(
            run("s", List.of(Mono.never()), List.of(), new ArrayList<>(List.of(resource))))
        .expectError(ExecutionTimeoutException.class)
        .verify(Duration.ofSeconds(5));

    verify(resource).dispose();
  }

  @Test
  void withoutSessionUsesDefaultTimeoutAndNeverAsksTheStore() {
    StepVerifier.create(run(null, List.of(), List.of(), new ArrayList<>())).verifyComplete();

    verify(configStore, never()).getExecutionTimeout(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void runDeadline_doesNotFireWhilePaused_butFiresAfterResume() {
    when(configStore.getExecutionTimeout("s")).thenReturn(Mono.just(60L));
    final AtomicBoolean paused = new AtomicBoolean(true);

    StepVerifier.withVirtualTime(
            () ->
                ExecutionResources.run(
                    configStore,
                    Schedulers.parallel(),
                    "s",
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
