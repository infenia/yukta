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
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class WorkerAgentTest {

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
  void drain_waitsForRunningAttempts_untilTheyEnd_orTheTimeoutOrAnInterrupt() {
    final java.util.concurrent.atomic.AtomicInteger running =
        new java.util.concurrent.atomic.AtomicInteger(2);
    final WorkerAgent waiting =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            running::get,
            Duration.ofSeconds(5));
    waiting.connected(mock(RSocketRequester.class));
    new Thread(
            () -> {
              try {
                Thread.sleep(300);
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              running.set(0);
            })
        .start();
    final long before = System.nanoTime();
    waiting.stop();
    assertThat(System.nanoTime() - before).isGreaterThan(Duration.ofMillis(200).toNanos());

    final WorkerAgent impatient =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            () -> 3,
            Duration.ofMillis(200));
    impatient.connected(mock(RSocketRequester.class));
    impatient.stop();

    final WorkerAgent interrupted =
        new WorkerAgent(
            reactor.core.publisher.Mono.just(mock(RSocketRequester.class)),
            "secret",
            new WorkerRegistration("w", Set.of(), Set.of(), 1),
            Duration.ofSeconds(1),
            () -> 3,
            Duration.ofSeconds(30));
    interrupted.connected(mock(RSocketRequester.class));
    Thread.currentThread().interrupt();
    try {
      interrupted.stop();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
