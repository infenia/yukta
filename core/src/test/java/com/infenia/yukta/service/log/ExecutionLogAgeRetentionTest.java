// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/** Runs that ended longer ago than the maximum age go every hour, on the leader only. */
@NoArgsConstructor
class ExecutionLogAgeRetentionTest {

  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

  private final ExecutionLogStore store = Mockito.mock(ExecutionLogStore.class);
  private final NodeCheckpointStore checkpoints = Mockito.mock(NodeCheckpointStore.class);
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final AtomicBoolean leader = new AtomicBoolean(true);
  private final ExecutionLogAgeRetention retention = retentionWith(Duration.ofDays(90));

  private ExecutionLogAgeRetention retentionWith(final Duration maxAge) {
    return new ExecutionLogAgeRetention(
        store,
        checkpoints,
        leader::get,
        maxAge,
        Duration.ofHours(1),
        scheduler,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    retention.stop();
  }

  @Test
  void theLeaderPurgesOldRunsAndTheirCheckpointsEveryHour() {
    when(store.purgeEndedBefore(any())).thenReturn(Mono.just(List.of("a", "b")));
    when(checkpoints.clearAsync(any())).thenReturn(Mono.empty());
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(1));

    Mockito.verify(store).purgeEndedBefore(NOW.minus(Duration.ofDays(90)));
    Mockito.verify(checkpoints).clearAsync("a");
    Mockito.verify(checkpoints).clearAsync("b");
    assertThat(retention.isRunning()).isTrue();
  }

  @Test
  void checkpointThatCannotBeClearedDoesNotStopTheOthers() {
    when(store.purgeEndedBefore(any())).thenReturn(Mono.just(List.of("a", "b")));
    when(checkpoints.clearAsync("a")).thenReturn(Mono.error(new IllegalStateException("down")));
    when(checkpoints.clearAsync("b")).thenReturn(Mono.empty());

    StepVerifier.create(retention.purge()).verifyComplete();

    Mockito.verify(checkpoints).clearAsync("b");
  }

  @Test
  void nothingRemovedIsNotAnError() {
    when(store.purgeEndedBefore(any())).thenReturn(Mono.just(List.of()));

    StepVerifier.create(retention.purge()).verifyComplete();
  }

  @Test
  void failedPurgeIsNotAnError() {
    when(store.purgeEndedBefore(any())).thenReturn(Mono.error(new IllegalStateException("down")));

    StepVerifier.create(retention.purge()).verifyComplete();
  }

  @Test
  void standbyDoesNotPurge() {
    leader.set(false);
    retention.start();

    scheduler.advanceTimeBy(Duration.ofHours(3));

    Mockito.verify(store, Mockito.never()).purgeEndedBefore(any());
  }

  @Test
  void withoutMaximumAgeNothingRuns() {
    final ExecutionLogAgeRetention none =
        new ExecutionLogAgeRetention(store, checkpoints, leader::get, YuktaSettings.bind(Map.of()));

    none.start();
    scheduler.advanceTimeBy(Duration.ofHours(3));

    assertThat(none.isRunning()).isFalse();
    StepVerifier.create(none.purge()).verifyComplete();
    Mockito.verify(store, Mockito.never()).purgeEndedBefore(any());
  }

  @Test
  void theMaximumAgeIsReadFromTheSettings() {
    final ExecutionLogAgeRetention fromSettings =
        new ExecutionLogAgeRetention(
            store,
            checkpoints,
            leader::get,
            YuktaSettings.bind(Map.of("yukta.execution-log.max-age", "30d")));

    fromSettings.start();
    assertThat(fromSettings.isRunning()).isTrue();
    fromSettings.stop();
    assertThat(fromSettings.isRunning()).isFalse();
  }

  @Test
  void maximumAgeMustBePositive() {
    assertThatThrownBy(() -> new YuktaProperties.ExecutionLog(1, 1, 0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("yukta.execution-log.max-age");
    assertThatThrownBy(() -> new YuktaProperties.ExecutionLog(1, 1, 0, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
