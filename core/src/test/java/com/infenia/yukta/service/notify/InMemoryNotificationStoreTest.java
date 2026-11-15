// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static com.infenia.yukta.service.notify.NotifyTestSupport.action;
import static com.infenia.yukta.service.notify.NotifyTestSupport.info;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
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
@SuppressWarnings("PMD.TooManyMethods")
class InMemoryNotificationStoreTest {

  private static final String INPUT_1 = "input:1";
  private static final String ARUN = "arun";

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
    service.raise(action(INPUT_1).build()).block();
    service.raise(info("i1").build()).block();
    service.raise(info("i2").build()).block();

    assertThat(store.find(NotificationQuery.ALL).map(Notification::key).collectList().block())
        .containsExactlyInAnyOrder(INPUT_1, "i2");
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
                ARUN,
                java.util.Map.of("input", NotificationPreferences.Level.OFF),
                java.util.Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    final InMemoryNotificationStore store = new InMemoryNotificationStore(1);
    assertThat(store.preferences(ARUN).block().levels()).isEmpty();
  }

  @Test
  void findFiltersByStateKindAndSessionAndResolveOfUnknownIsEmpty() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service = serviceOver(store);
    service.raise(action(INPUT_1).sessionId("s1").build()).block();
    service.raise(info("i").sessionId("s2").build()).block();
    service.resolve(INPUT_1, "done").block();

    assertThat(count(store, new NotificationQuery(Notification.State.RESOLVED, null, null, 0)))
        .isEqualTo(1);
    assertThat(count(store, new NotificationQuery(null, NotificationKind.INFO, null, 0)))
        .isEqualTo(1);
    assertThat(count(store, new NotificationQuery(null, null, "s2", 0))).isEqualTo(1);
    assertThat(store.findByKey("i").block().key()).isEqualTo("i");
    assertThat(store.findByKey("nope").block()).isNull();
    assertThat(store.resolve("nope", "x", clock.instant()).block()).isNull();
    assertThat(store.resolve(INPUT_1, "again", clock.instant()).block()).isNull();
    assertThat(store.findById("nope").block()).isNull();
  }

  private static long count(final InMemoryNotificationStore store, final NotificationQuery query) {
    return store.find(query).count().block();
  }

  @Test
  void activeActionsAreNeverEvictedEvenOverTheBound() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(1);
    final NotificationService service = serviceOver(store);
    service.raise(action(INPUT_1).build()).block();
    service.raise(action("input:2").build()).block();

    assertThat(count(store, NotificationQuery.ALL)).isEqualTo(2);
  }

  @Test
  void savedPreferencesComeBackAndPurgeDropsTheReceiptsOfRemovedItems() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service = serviceOver(store);
    store
        .savePreferences(
            new NotificationPreferences(
                ARUN,
                java.util.Map.of("run.failed", NotificationPreferences.Level.DESKTOP),
                java.util.Set.of("s1")))
        .block();
    final Notification n = service.raise(info("i").build()).block();
    service.markRead(ARUN, List.of(n.id())).block();
    clock.advance(Duration.ofDays(8));
    store.purge(clock.instant()).block();

    assertThat(store.preferences(ARUN).block().levels()).containsKey("run.failed");
    assertThat(store.receipts(ARUN, List.of(n.id())).collectList().block()).isEmpty();
  }

  @Test
  void deliveriesAreDueWhenPendingAndTimeComesAndGoWithTheirPurgedNotification() {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service = serviceOver(store);
    final Notification n = service.raise(info("i").build()).block();
    final NotificationDelivery later =
        NotificationDelivery.pending(n.id(), "webhook:a", null, clock.instant().plusSeconds(60));
    final NotificationDelivery now =
        NotificationDelivery.pending(n.id(), "webhook:b", null, clock.instant());
    store.enqueueDelivery(later).block();
    store.enqueueDelivery(now).block();

    assertThat(store.enqueueDelivery(now.sent()).block()).isEqualTo(now);
    assertThat(store.dueDeliveries(clock.instant()).collectList().block()).containsExactly(now);
    store.saveDelivery(now.sent()).block();
    store
        .saveDelivery(NotificationDelivery.pending("gone", "webhook:c", null, clock.instant()))
        .block();
    assertThat(store.dueDeliveries(clock.instant().plusSeconds(120)).collectList().block())
        .containsExactly(later);

    clock.advance(Duration.ofDays(8));
    store.purge(clock.instant()).block();

    assertThat(store.dueDeliveries(clock.instant().plusSeconds(120)).collectList().block())
        .isEmpty();
  }
}
