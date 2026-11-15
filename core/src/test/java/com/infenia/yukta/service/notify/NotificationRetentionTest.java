// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

@NoArgsConstructor
class NotificationRetentionTest {

  @Test
  void purgeRemovesWhatIsOlderThanTheRetention() {
    final MutableClock clock = new MutableClock();
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service =
        new NotificationService(store, new InputRedactor(List.of()), clock);
    service.raise(NotifyTestSupport.info("old").build()).block();
    clock.advance(Duration.ofDays(8));
    service.raise(NotifyTestSupport.info("new").build()).block();
    final NotificationRetention retention =
        new NotificationRetention(
            store,
            () -> true,
            Duration.ofDays(7),
            Duration.ofHours(1),
            Schedulers.parallel(),
            clock);

    retention.purge().block();

    assertThat(store.find(NotificationQuery.ALL).count().block()).isEqualTo(1);
  }

  @Test
  void failingStoreIsLoggedNotRaisedAndTheTimerStartsAndStops() {
    final NotificationStore broken = org.mockito.Mockito.mock(NotificationStore.class);
    org.mockito.Mockito.when(broken.purge(org.mockito.ArgumentMatchers.any()))
        .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("down")));
    final NotificationRetention retention =
        new NotificationRetention(
            broken,
            () -> true,
            Duration.ofDays(7),
            Duration.ofHours(1),
            Schedulers.parallel(),
            new MutableClock());

    retention.purge().block();
    assertThat(retention.isRunning()).isFalse();
    retention.start();
    assertThat(retention.isRunning()).isTrue();
    retention.stop();
    assertThat(retention.isRunning()).isFalse();
  }

  @Test
  void theSpringConstructorReadsTheRetentionFromTheSettings() {
    final var properties =
        new YuktaProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            new YuktaProperties.Notifications(
                true, Duration.ofDays(3), 10, Duration.ZERO, Map.of(), Map.of()),
            null,
            null,
            null,
            null,
            null,
            null);

    final NotificationRetention retention =
        new NotificationRetention(new InMemoryNotificationStore(1), () -> true, properties);

    assertThat(retention.isRunning()).isFalse();
  }

  @Test
  void purgingNothingRemovesNothing() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(1);
    final NotificationRetention retention =
        new NotificationRetention(
            store,
            () -> true,
            Duration.ofDays(7),
            Duration.ofHours(1),
            Schedulers.parallel(),
            new MutableClock());

    retention.purge().block();

    assertThat(store.find(NotificationQuery.ALL).count().block()).isZero();
  }
}
