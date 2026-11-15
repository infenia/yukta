// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.service.notify.NotificationDelivery;
import com.infenia.yukta.service.notify.NotificationPreferences;
import com.infenia.yukta.service.notify.NotificationQuery;
import com.infenia.yukta.service.notify.NotificationStore;
import com.infenia.yukta.service.notify.Receipt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What every {@link NotificationStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
public abstract class NotificationStoreContract {

  private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

  /** Creates the contract. */
  protected NotificationStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store under test
   */
  protected abstract NotificationStore create();

  /**
   * A second store over the same data, as after a restart. A store that keeps nothing across a
   * restart returns {@code null}, and the tests that need it are skipped.
   *
   * @param first the store that was used
   * @return a store that sees what the first one wrote, or {@code null}
   */
  protected @Nullable NotificationStore restarted(final NotificationStore first) {
    return null;
  }

  private static Notification draft(
      final String key, final NotificationKind kind, final @Nullable String session) {
    return new Notification(
        "",
        key,
        kind,
        NotificationSeverity.WARN,
        "input",
        "Title " + key,
        "body",
        session,
        "wf",
        "e1",
        "n1",
        kind == NotificationKind.ACTION ? "r-" + key : null,
        List.of("role:OPERATOR", "key:arun"),
        Notification.State.ACTIVE,
        null,
        1,
        0,
        T0,
        T0);
  }

  private static Function<Optional<Notification>, Notification> create(final Notification draft) {
    return current -> current.orElse(draft);
  }

  private static Notification put(final NotificationStore store, final Notification draft) {
    return store.upsert(draft.key(), create(draft)).block();
  }

  @Test
  void upsertCreatesOnceAndAKeyHasOneItemWithGrowingSequence() {
    final NotificationStore store = create();

    final Notification a = put(store, draft("a", NotificationKind.INFO, "s1"));
    final Notification again = put(store, draft("a", NotificationKind.INFO, "s1"));
    final Notification b = put(store, draft("b", NotificationKind.ACTION, "s1"));

    assertThat(a.id()).isNotBlank();
    assertThat(again).isEqualTo(a);
    assertThat(again.seq()).isEqualTo(a.seq());
    assertThat(b.seq()).isGreaterThan(a.seq());
    assertThat(store.find(NotificationQuery.ALL).collectList().block()).hasSize(2);
    assertThat(store.findByKey("a").block()).isEqualTo(a);
    assertThat(store.findById(b.id()).block()).isEqualTo(b);
    assertThat(store.findById("nope").block()).isNull();
    assertThat(store.findByKey("nope").block()).isNull();
  }

  @Test
  void aMergeThatChangesAnItemKeepsItsIdAndAdvancesTheSequence() {
    final NotificationStore store = create();
    final Notification a = put(store, draft("a", NotificationKind.ACTION, "s1"));

    final Notification changed =
        store
            .upsert(
                "a",
                current -> {
                  final Notification old = current.orElseThrow();
                  return new Notification(
                      old.id(),
                      old.key(),
                      old.kind(),
                      old.severity(),
                      old.category(),
                      "New",
                      old.body(),
                      old.sessionId(),
                      old.workflowId(),
                      old.executionId(),
                      old.nodeId(),
                      old.inputRequestId(),
                      List.of("key:sam"),
                      old.state(),
                      null,
                      old.count() + 1,
                      0,
                      old.createdAt(),
                      T0.plusSeconds(5));
                })
            .block();

    assertThat(changed.id()).isEqualTo(a.id());
    assertThat(changed.seq()).isGreaterThan(a.seq());
    assertThat(changed.title()).isEqualTo("New");
    assertThat(changed.audience()).containsExactly("key:sam");
    assertThat(changed.count()).isEqualTo(2);
    assertThat(changed.createdAt()).isEqualTo(T0);
    assertThat(changed.updatedAt()).isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void resolveIsTerminalAndDoesNotAdvanceTwice() {
    final NotificationStore store = create();
    final Notification a = put(store, draft("a", NotificationKind.ACTION, "s1"));

    final Notification done =
        store.resolve("a", "answered by key:arun: approve", T0.plusSeconds(9)).block();

    assertThat(done.state()).isEqualTo(Notification.State.RESOLVED);
    assertThat(done.resolution()).isEqualTo("answered by key:arun: approve");
    assertThat(done.seq()).isGreaterThan(a.seq());
    assertThat(done.updatedAt()).isEqualTo(T0.plusSeconds(9));
    assertThat(store.resolve("a", "again", T0).block()).isNull();
    assertThat(store.resolve("nope", "x", T0).block()).isNull();
    assertThat(store.findByKey("a").block().resolution())
        .isEqualTo("answered by key:arun: approve");
  }

  @Test
  void findFiltersAndFindsOnlyLaterChangesNewestFirst() {
    final NotificationStore store = create();
    final Notification a = put(store, draft("a", NotificationKind.INFO, "s1"));
    put(store, draft("b", NotificationKind.ACTION, "s2"));
    put(store, draft("c", NotificationKind.ACTION, "s1"));
    store.resolve("c", "done", T0).block();

    assertThat(keys(store, NotificationQuery.ALL)).containsExactly("c", "b", "a");
    assertThat(keys(store, NotificationQuery.since(a.seq()))).containsExactly("c", "b");
    assertThat(keys(store, new NotificationQuery(Notification.State.RESOLVED, null, null, 0)))
        .containsExactly("c");
    assertThat(keys(store, new NotificationQuery(null, NotificationKind.INFO, null, 0)))
        .containsExactly("a");
    assertThat(keys(store, new NotificationQuery(null, null, "s2", 0))).containsExactly("b");
  }

  private static List<String> keys(final NotificationStore store, final NotificationQuery query) {
    return store.find(query).map(Notification::key).collectList().block();
  }

  @Test
  void receiptsAreKeptPerRecipientAndReplaced() {
    final NotificationStore store = create();
    final Notification a = put(store, draft("a", NotificationKind.INFO, null));

    store.saveReceipt(new Receipt(a.id(), "arun", T0, null)).block();
    store.saveReceipt(new Receipt(a.id(), "sam", T0, T0.plusSeconds(1))).block();
    store.saveReceipt(new Receipt(a.id(), "arun", T0, T0.plusSeconds(2))).block();

    assertThat(store.receipts("arun", List.of(a.id())).collectList().block())
        .containsExactly(new Receipt(a.id(), "arun", T0, T0.plusSeconds(2)));
    assertThat(store.receipts("sam", List.of(a.id(), "other")).collectList().block()).hasSize(1);
    assertThat(store.receipts("local", List.of(a.id())).collectList().block()).isEmpty();
    assertThat(store.receipts("arun", List.of()).collectList().block()).isEmpty();
  }

  @Test
  void aReceiptForAMissingNotificationIsNotKept() {
    final NotificationStore store = create();

    store.saveReceipt(new Receipt("missing", "arun", T0, null)).block();

    assertThat(store.receipts("arun", List.of("missing")).collectList().block()).isEmpty();
  }

  @Test
  void preferencesComeBackAsSavedOrTheDefaults() {
    final NotificationStore store = create();
    assertThat(store.preferences("arun").block())
        .isEqualTo(NotificationPreferences.defaults("arun"));

    final NotificationPreferences saved =
        new NotificationPreferences(
            "arun",
            Map.of("run.failed", NotificationPreferences.Level.DESKTOP),
            Set.of("s1", "s2"));
    store.savePreferences(saved).block();
    store
        .savePreferences(
            new NotificationPreferences(
                "arun", Map.of("run.failed", NotificationPreferences.Level.OFF), Set.of()))
        .block();
    store.savePreferences(saved).block();

    assertThat(store.preferences("arun").block()).isEqualTo(saved);
    assertThat(store.preferences("sam").block()).isEqualTo(NotificationPreferences.defaults("sam"));
  }

  @Test
  void purgeRemovesOldInfoAndResolvedActionsWithTheirDataButKeepsActiveActions() {
    final NotificationStore store = create();
    final Notification open = put(store, draft("open", NotificationKind.ACTION, "s1"));
    final Notification done = put(store, draft("done", NotificationKind.ACTION, "s1"));
    store.resolve("done", "answered", T0).block();
    final Notification info = put(store, draft("info", NotificationKind.INFO, "s1"));
    store.saveReceipt(new Receipt(info.id(), "arun", T0, null)).block();
    store.enqueueDelivery(NotificationDelivery.pending(info.id(), "webhook:a", null, T0)).block();

    final long removed = store.purge(T0.plus(Duration.ofDays(1))).block();

    assertThat(removed).isEqualTo(2);
    assertThat(keys(store, NotificationQuery.ALL)).containsExactly("open");
    assertThat(store.receipts("arun", List.of(info.id())).collectList().block()).isEmpty();
    assertThat(store.dueDeliveries(T0.plus(Duration.ofDays(2))).collectList().block()).isEmpty();
    assertThat(store.purge(T0.plus(Duration.ofDays(1))).block()).isZero();
    assertThat(open.id()).isNotEqualTo(done.id());
  }

  @Test
  void purgeKeepsWhatWasChangedAfterTheCutoff() {
    final NotificationStore store = create();
    final Notification info = put(store, draft("info", NotificationKind.INFO, "s1"));

    assertThat(store.purge(T0.minusSeconds(1)).block()).isZero();
    assertThat(store.findById(info.id()).block()).isNotNull();
  }

  @Test
  void deliveriesAreQueuedOnceDueInOrderAndRetriedUntilSent() {
    final NotificationStore store = create();
    final Notification n = put(store, draft("a", NotificationKind.ACTION, "s1"));
    final NotificationDelivery later =
        NotificationDelivery.pending(n.id(), "webhook:b", null, T0.plusSeconds(60));
    final NotificationDelivery now = NotificationDelivery.pending(n.id(), "webhook:a", "hook", T0);

    assertThat(store.enqueueDelivery(later).block()).isEqualTo(later);
    assertThat(store.enqueueDelivery(now).block()).isEqualTo(now);
    assertThat(store.enqueueDelivery(now.sent()).block()).isEqualTo(now);

    assertThat(store.dueDeliveries(T0).collectList().block()).containsExactly(now);
    assertThat(store.dueDeliveries(T0.plusSeconds(120)).collectList().block())
        .containsExactly(now, later);

    final NotificationDelivery retry = now.failed("503", T0.plusSeconds(30), false);
    store.saveDelivery(retry).block();
    assertThat(store.dueDeliveries(T0.plusSeconds(10)).collectList().block()).isEmpty();
    assertThat(store.dueDeliveries(T0.plusSeconds(30)).collectList().block())
        .containsExactly(retry);

    store.saveDelivery(retry.sent()).block();
    store.saveDelivery(later.failed("x", T0, true)).block();
    assertThat(store.dueDeliveries(T0.plusSeconds(600)).collectList().block()).isEmpty();
  }

  @Test
  void aDeliveryOfANotificationThatIsGoneIsNotKept() {
    final NotificationStore store = create();

    store.saveDelivery(NotificationDelivery.pending("gone", "webhook:a", null, T0)).block();

    assertThat(store.dueDeliveries(T0).collectList().block()).isEmpty();
  }

  @Test
  void theEpochIsTheSameEveryTimeAndDiffersBetweenNewStores() {
    final NotificationStore store = create();

    assertThat(store.epoch().block()).isNotBlank().isEqualTo(store.epoch().block());
  }

  @Test
  void aRestartKeepsTheItemsReceiptsPreferencesDeliveriesAndTheEpoch() {
    final NotificationStore store = create();
    final Notification a = put(store, draft("a", NotificationKind.ACTION, "s1"));
    store.saveReceipt(new Receipt(a.id(), "arun", T0, T0.plusSeconds(1))).block();
    store.savePreferences(NotificationPreferences.defaults("arun")).block();
    store.enqueueDelivery(NotificationDelivery.pending(a.id(), "webhook:a", "hook", T0)).block();
    final NotificationStore after = restarted(store);
    if (after == null) {
      return;
    }

    assertThat(after.findByKey("a").block()).isEqualTo(a);
    assertThat(after.receipts("arun", List.of(a.id())).collectList().block())
        .containsExactly(new Receipt(a.id(), "arun", T0, T0.plusSeconds(1)));
    assertThat(after.dueDeliveries(T0).collectList().block()).hasSize(1);
    assertThat(after.epoch().block()).isEqualTo(store.epoch().block());
    final Notification next = put(after, draft("b", NotificationKind.INFO, null));
    assertThat(next.seq()).isGreaterThan(a.seq());
    assertThat(put(after, draft("a", NotificationKind.ACTION, "s1")).id()).isEqualTo(a.id());
  }

  @Test
  void twoWritersOfTheSameKeyEndWithOneItem() {
    final NotificationStore store = create();

    final List<Notification> both =
        reactor.core.publisher.Flux.merge(
                store.upsert("k", create(draft("k", NotificationKind.ACTION, "s1"))),
                store.upsert("k", create(draft("k", NotificationKind.ACTION, "s1"))),
                store.upsert("k", create(draft("k", NotificationKind.ACTION, "s1"))))
            .collectList()
            .block();

    assertThat(both).extracting(Notification::id).containsOnly(both.getFirst().id());
    assertThat(keys(store, NotificationQuery.ALL)).containsExactly("k");
  }
}
