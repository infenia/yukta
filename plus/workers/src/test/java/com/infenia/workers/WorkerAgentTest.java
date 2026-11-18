// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.service.worker.WorkerRegistration;
import java.time.Duration;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.rsocket.RSocketRequester;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkerAgentTest {

  @Test
  void session_rejectFrame_failsOnceWithTheControlPlanesReason() {
    final RSocketRequester requester =
        mock(RSocketRequester.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    org.mockito.Mockito.when(
            requester
                .route("worker.session")
                .data(org.mockito.ArgumentMatchers.any())
                .retrieveFlux(WorkerFrame.class))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                WorkerFrame.lease(0, false), WorkerFrame.reject("token refused")));
    final WorkerAgent agent =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(requester),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            () -> 0,
            Duration.ofSeconds(1));

    reactor.test.StepVerifier.create(agent.session())
        .expectErrorMessage("Refused by the control plane: token refused")
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void stopBeforeStart_isHarmless() {
    final WorkerAgent agent =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            () -> 0,
            Duration.ofSeconds(1));

    assertThatCode(agent::stop).doesNotThrowAnyException();
  }

  @Test
  void stopClosesTheOpenConnection_ifThereIsOne() {
    final WorkerAgent agent =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            () -> 0,
            Duration.ofSeconds(1));
    final RSocketRequester withSocket = mock(RSocketRequester.class);
    final io.rsocket.RSocket socket = mock(io.rsocket.RSocket.class);
    org.mockito.Mockito.when(withSocket.rsocket()).thenReturn(socket);

    agent.connected(mock(RSocketRequester.class));
    assertThatCode(agent::stop).doesNotThrowAnyException();
    agent.connected(withSocket);
    agent.stop();

    org.mockito.Mockito.verify(socket).dispose();
  }

  @Test
  void stop_waitsForRunningAttempts_untilTheyEnd_orTheTimeout_withoutParkingTheCaller() {
    final java.util.concurrent.atomic.AtomicInteger running =
        new java.util.concurrent.atomic.AtomicInteger(2);
    final WorkerAgent waiting = agent(running::get, Duration.ofSeconds(5));
    waiting.connected(mock(RSocketRequester.class));
    final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);

    waiting.stop(closed::countDown);

    // stop returned at once and the attempts still run, so nothing is closed yet
    assertThat(closed.getCount()).isEqualTo(1);
    running.set(0);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> closed.getCount() == 0);

    final java.util.concurrent.CountDownLatch late = new java.util.concurrent.CountDownLatch(1);
    final WorkerAgent impatient = agent(() -> 3, Duration.ofMillis(200));
    impatient.connected(mock(RSocketRequester.class));
    impatient.stop(late::countDown);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> late.getCount() == 0);
  }

  @Test
  void isRunning_isTrueFromStartUntilStop() {
    final WorkerAgent agent = agent(() -> 0, Duration.ofSeconds(1));
    assertThat(agent.isRunning()).isFalse();

    agent.start();
    assertThat(agent.isRunning()).isTrue();
    agent.stop();

    assertThat(agent.isRunning()).isFalse();
  }

  private static WorkerAgent agent(
      final java.util.function.IntSupplier used, final Duration drainTimeout) {
    return new WorkerAgent(
        reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
        "secret",
        new WorkerRegistration("w", Set.of(), Set.of(), 1),
        Duration.ofSeconds(1),
        used,
        drainTimeout);
  }
}
