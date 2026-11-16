// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static com.infenia.yukta.service.agent.AgentTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/** The purge of old ended conversations runs every hour, on the leader only. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class AgentRetentionTest {

  private final AgentConversationStore store = mock(AgentConversationStore.class);
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final AtomicBoolean leader = new AtomicBoolean(true);
  private final AgentRetention retention =
      new AgentRetention(
          store,
          leader::get,
          Duration.ofDays(7),
          Duration.ofHours(1),
          scheduler,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void tearDown() {
    retention.stop();
  }

  @Test
  void theLeaderPurgesWhatEndedBeforeTheRetentionEveryHour() {
    when(store.purge(any())).thenReturn(Mono.just(3L));
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(1));

    verify(store).purge(NOW.minus(Duration.ofDays(7)));
    assertThat(retention.isRunning()).isTrue();
  }

  @Test
  void nothingRemovedIsNotLogged() {
    when(store.purge(any())).thenReturn(Mono.just(0L));

    StepVerifier.create(retention.purge()).verifyComplete();
  }

  @Test
  void standbyServerDoesNotPurge() {
    leader.set(false);
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(3));

    verify(store, never()).purge(any());
  }

  @Test
  void failedPurgeIsLoggedNotRaised() {
    when(store.purge(any())).thenReturn(Mono.error(new IllegalStateException("db down")));

    StepVerifier.create(retention.purge()).verifyComplete();
  }

  @Test
  void stoppingStopsTheTimer() {
    retention.start();
    retention.stop();

    assertThat(retention.isRunning()).isFalse();
  }
}
