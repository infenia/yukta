// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/** The purge of old finished requests runs every hour, on the leader only. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyStaticImports")
class InputRetentionTest {

  private final InputRequestStore store = mock(InputRequestStore.class);
  private final MutableClock clock = new MutableClock();
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final AtomicBoolean leader = new AtomicBoolean(true);
  private final InputRetention retention =
      new InputRetention(
          store, leader::get, Duration.ofDays(30), Duration.ofHours(1), scheduler, clock);

  @AfterEach
  void tearDown() {
    retention.stop();
  }

  @Test
  void theLeaderPurgesWhatIsOlderThanTheRetentionEveryHour() {
    when(store.purgeTerminalOlderThan(any())).thenReturn(Mono.just(3L));
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(1));

    verify(store)
        .purgeTerminalOlderThan(Instant.parse("2026-11-02T10:00:00Z").minus(Duration.ofDays(30)));
    assertThat(retention.isRunning()).isTrue();
  }

  @Test
  void nothingRemovedIsNotLogged() {
    when(store.purgeTerminalOlderThan(any())).thenReturn(Mono.just(0L));

    StepVerifier.create(retention.purge()).verifyComplete();
  }

  @Test
  void standbyDoesNotPurge() {
    leader.set(false);
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(3));

    verify(store, never()).purgeTerminalOlderThan(any());
  }

  @Test
  void failedPurgeIsNotAnError() {
    when(store.purgeTerminalOlderThan(any()))
        .thenReturn(Mono.error(new IllegalStateException("down")));

    StepVerifier.create(retention.purge()).verifyComplete();
  }

  @Test
  void startingTwiceKeepsOneTimerAndStopEndsIt() {
    when(store.purgeTerminalOlderThan(any())).thenReturn(Mono.just(0L));
    retention.start();
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(1));
    retention.stop();
    retention.stop();
    scheduler.advanceTimeBy(Duration.ofHours(2));

    verify(store).purgeTerminalOlderThan(any());
    assertThat(retention.isRunning()).isFalse();
  }

  @Test
  void settingsDecideHowLongRequestsAreKept() {
    final InputRetention fromSettings =
        new InputRetention(
            store,
            leader::get,
            com.infenia.yukta.testsupport.YuktaSettings.bind(java.util.Map.of()));

    fromSettings.start();
    assertThat(fromSettings.isRunning()).isTrue();
    fromSettings.stop();
  }
}
