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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.messaging.rsocket.RSocketRequester;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkerAgentTest {

  @Test
  void session_rejectFrame_failsOnceWithTheControlPlanesReason_andClosesTheConnection() {
    final RSocketRequester requester = mockRequester();
    final WorkerAgent agent =
        sessionAgent(
            requester,
            reactor.core.publisher.Flux.just(
                WorkerFrame.ack(), WorkerFrame.reject("token refused")));

    // the ACK comes through, so the reconnect pause starts over after a healthy connection
    reactor.test.StepVerifier.create(agent.session())
        .expectNextMatches(frame -> frame.type() == WorkerFrame.Type.ACK)
        .expectErrorMessage("Refused by the control plane: token refused")
        .verify(Duration.ofSeconds(5));
    org.mockito.Mockito.verify(requester).dispose();
  }

  @Test
  void session_endedByTheControlPlane_failsAndClosesTheConnection() {
    final RSocketRequester requester = mockRequester();
    final WorkerAgent agent = sessionAgent(requester, reactor.core.publisher.Flux.empty());

    reactor.test.StepVerifier.create(agent.session())
        .expectErrorMessage("The control plane closed the connection")
        .verify(Duration.ofSeconds(5));
    org.mockito.Mockito.verify(requester).dispose();
  }

  /* default */
  @Nested
  class Readiness {

    @Test
    void readiness_followsRegistration_andRefusesBeforeTheConnectionCloses() {
      final RSocketRequester requester = mockRequester();
      final WorkerAgent agent =
          sessionAgent(requester, reactor.core.publisher.Flux.just(WorkerFrame.ack()));
      final java.util.List<Object> states = new java.util.ArrayList<>();
      agent.setApplicationEventPublisher(event -> {
        if (event instanceof AvailabilityChangeEvent<?> change) {
          states.add(change.getState());
        }
      });
      org.mockito.Mockito.doAnswer(
              call -> {
                states.add("closed");
                return null;
              })
          .when(requester)
          .dispose();

      // ACK, then the stream ends: accepting first, refusing before the connection is closed
      reactor.test.StepVerifier.create(agent.session())
          .expectNextCount(1)
          .expectError()
          .verify(Duration.ofSeconds(5));

      assertThat(states)
          .containsExactly(
              org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC,
              org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC,
              "closed");
    }
  }

  private static RSocketRequester mockRequester() {
    return mock(RSocketRequester.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
  }

  private static WorkerAgent sessionAgent(
      final RSocketRequester requester, final reactor.core.publisher.Flux<WorkerFrame> answers) {
    org.mockito.Mockito.when(
            requester
                .route("worker.session")
                .data(org.mockito.ArgumentMatchers.any())
                .retrieveFlux(WorkerFrame.class))
        .thenReturn(answers);
    return new WorkerAgent(
        reactor.core.publisher.Mono.just(requester),
        "secret",
        new WorkerRegistration("w", Set.of(), Set.of(), 1),
        Duration.ofSeconds(1),
        () -> 0,
        Duration.ofSeconds(1));
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
    final RSocketRequester open = mock(RSocketRequester.class);

    agent.connected(open);
    agent.stop();

    org.mockito.Mockito.verify(open).dispose();
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
  void stopCalledAgainWhileDraining_doesNotWaitForTheRunningAttemptsAgain() {
    final java.util.concurrent.atomic.AtomicInteger running =
        new java.util.concurrent.atomic.AtomicInteger(2);
    final WorkerAgent agent = agent(running::get, Duration.ofSeconds(5));
    agent.connected(mock(RSocketRequester.class));
    final java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch second = new java.util.concurrent.CountDownLatch(1);

    agent.stop(first::countDown);
    agent.stop(second::countDown);

    // the second stop finds the drain under way and returns at once, while the first still waits
    assertThat(second.getCount()).isZero();
    assertThat(first.getCount()).isEqualTo(1);
    running.set(0);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> first.getCount() == 0);
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
