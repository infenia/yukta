// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static com.infenia.yukta.service.notify.NotifyTestSupport.action;
import static com.infenia.yukta.service.notify.NotifyTestSupport.info;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.redact.InputRedactor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressFBWarnings(
    value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
    justification = "block() of a store call that always emits")
class InMemoryNotificationStoreTest {

  private final MutableClock clock = new MutableClock();

  private NotificationService serviceOver(final InMemoryNotificationStore store) {
    return new NotificationService(store, new InputRedactor(List.of()), clock);
  }

  @Test
  void boundOfZeroIsRefused() {
    assertThatThrownBy(() -> new InMemoryNotificationStore(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sequenceStrictlyIncreasesAndSinceFindsOnlyLaterChanges() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service = serviceOver(store);
    final long a = service.raise(info("a").build()).block().seq();
    final long b = service.raise(info("b").build()).block().seq();

    assertThat(b).isGreaterThan(a);
    assertThat(store.find(NotificationQuery.since(a)).map(Notification::key).collectList().block())
        .containsExactly("b");
  }

  @Test
  void overTheBoundTheOldestInfoGoesAndAnActiveActionStays() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(2);
    final NotificationService service = serviceOver(store);
    service.raise(action("input:1").build()).block();
    service.raise(info("i1").build()).block();
    service.raise(info("i2").build()).block();

    assertThat(store.find(NotificationQuery.ALL).map(Notification::key).collectList().block())
        .containsExactlyInAnyOrder("input:1", "i2");
  }

  @Test
  void purgeRemovesOldInfoAndResolvedActionsButKeepsActiveActions() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service = serviceOver(store);
    service.raise(action("input:open").build()).block();
    service.raise(action("input:done").build()).block();
    service.resolve("input:done", "answered").block();
    service.raise(info("i").build()).block();
    clock.advance(Duration.ofDays(8));

    final long removed = store.purge(clock.instant().minus(Duration.ofDays(7))).block();

    assertThat(removed).isEqualTo(2);
    assertThat(store.find(NotificationQuery.ALL).map(Notification::key).collectList().block())
        .containsExactly("input:open");
  }

  @Test
  void eachStoreHasItsOwnEpoch() {
    assertThat(new InMemoryNotificationStore(1).epoch())
        .isNotEqualTo(new InMemoryNotificationStore(1).epoch());
  }

  @Test
  void inputPreferencesCannotTurnInputOff() {
    assertThatThrownBy(() ->
            new NotificationPreferences(
                "arun",
                java.util.Map.of("input", NotificationPreferences.Level.OFF),
                java.util.Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    final InMemoryNotificationStore store = new InMemoryNotificationStore(1);
    assertThat(store.preferences("arun").block().levels()).isEmpty();
  }
}
