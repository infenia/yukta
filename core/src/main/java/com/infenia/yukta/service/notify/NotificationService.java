// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.util.SinkEmit;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Writes notifications: raises or updates one by key, ends it, and keeps what each person read or
 * dismissed. Every change is also sent to {@link #changes()}, for the stream. Nothing here decides
 * whether a question is still open: that stays the input request's state.
 */
@Component
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
public class NotificationService {

  /** The store. */
  private final NotificationStore store;

  /** Cleans the text of secrets. */
  private final InputRedactor redactor;

  /** The time of a change. */
  private final Clock clock;

  /** Every change, for the subscribers of the stream. */
  private final Sinks.Many<NotificationChange> changeSink =
      Sinks.many().multicast().directBestEffort();

  /**
   * Creates the service.
   *
   * @param store where notifications are kept
   * @param redactor cleans titles and bodies
   */
  @Autowired
  public NotificationService(final NotificationStore store, final InputRedactor redactor) {
    this(store, redactor, Clock.systemUTC());
  }

  /**
   * Creates the service with an explicit clock.
   *
   * @param store where notifications are kept
   * @param redactor cleans titles and bodies
   * @param clock the time of a change
   */
  public NotificationService(
      final NotificationStore store, final InputRedactor redactor, final Clock clock) {
    this.store = store;
    this.redactor = redactor;
    this.clock = clock;
  }

  /**
   * Raises a notification, or changes the active one of the same key. A repeat inside the draft's
   * cooldown only raises the count. A resolved one never becomes active again: the repeat is
   * ignored and the resolved item is returned.
   *
   * @param draft what to raise
   * @return the notification as stored
   */
  public Mono<Notification> raise(final NotificationDraft draft) {
    final AtomicReference<NotificationChange.Kind> happened = new AtomicReference<>();
    final Instant now = clock.instant();
    return store
        .upsert(draft.key(), current -> merge(draft, current.orElse(null), now, happened))
        .doOnNext(stored -> {
          final NotificationChange.Kind kind = happened.get();
          if (kind != null) {
            publish(new NotificationChange(kind, stored, null));
            if (kind == NotificationChange.Kind.CREATED) {
              Metrics.counter(
                      "yukta.notifications.raised",
                      "category",
                      stored.category(),
                      "kind",
                      stored.kind().name())
                  .increment();
            }
          }
        });
  }

  private Notification merge(
      final NotificationDraft draft,
      final @Nullable Notification current,
      final Instant now,
      final AtomicReference<NotificationChange.Kind> happened) {
    if (current != null && current.state() == Notification.State.RESOLVED) {
      return current;
    }
    happened.set(
        current == null ? NotificationChange.Kind.CREATED : NotificationChange.Kind.UPDATED);
    return draftOf(
        draft,
        current == null ? "" : current.id(),
        count(draft, current, now),
        current == null ? now : current.createdAt(),
        now);
  }

  /** A repeat inside the cooldown raises the count; anything else keeps it. */
  private static int count(
      final NotificationDraft draft, final @Nullable Notification current, final Instant now) {
    if (current == null) {
      return 1;
    }
    final boolean repeat =
        !draft.cooldown().isZero() && now.isBefore(current.updatedAt().plus(draft.cooldown()));
    return current.count() + (repeat ? 1 : 0);
  }

  private Notification draftOf(
      final NotificationDraft draft,
      final String id,
      final int count,
      final Instant createdAt,
      final Instant now) {
    return new Notification(
        id,
        draft.key(),
        draft.kind(),
        draft.severity(),
        draft.category(),
        clip(redactor.text(draft.title()), Notification.TITLE_MAX),
        draft.body() == null ? null : clip(redactor.text(draft.body()), Notification.BODY_MAX),
        draft.sessionId(),
        draft.workflowId(),
        draft.executionId(),
        draft.nodeId(),
        draft.inputRequestId(),
        draft.audience(),
        Notification.State.ACTIVE,
        null,
        count,
        0,
        createdAt,
        now);
  }

  private static String clip(final String text, final int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  /**
   * Ends the notification of a key. Ending is terminal; ending a resolved or unknown one does
   * nothing.
   *
   * @param key the dedupe key
   * @param resolution how it ended, such as {@code expired}
   * @return the resolved notification; empty when there was nothing to resolve
   */
  public Mono<Notification> resolve(final String key, final String resolution) {
    return store
        .resolve(key, clip(redactor.text(resolution), Notification.TITLE_MAX), clock.instant())
        .doOnNext(n -> publish(new NotificationChange(NotificationChange.Kind.RESOLVED, n, null)));
  }

  /**
   * Marks notifications read for a person; one already read keeps its first time.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @param ids the notification ids
   * @return completes when saved
   */
  public Mono<Void> markRead(final String recipient, final Collection<String> ids) {
    final Instant now = clock.instant();
    return Flux.fromIterable(ids)
        .concatMap(id ->
            receipt(
                recipient,
                id,
                current ->
                    new Receipt(
                        id,
                        recipient,
                        current.readAt() == null ? now : current.readAt(),
                        current.dismissedAt())))
        .then();
  }

  /**
   * Dismisses a notification for a person. An active action cannot be dismissed: it goes when its
   * request ends.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @param id the notification id
   * @return completes when saved; fails with {@link IllegalStateException} for an active action and
   *     {@link NoSuchElementException} for an unknown id
   */
  public Mono<Void> dismiss(final String recipient, final String id) {
    final Instant now = clock.instant();
    return store
        .findById(id)
        .switchIfEmpty(Mono.error(() -> new NoSuchElementException(id)))
        .flatMap(n -> {
          if (n.kind() == NotificationKind.ACTION && n.state() == Notification.State.ACTIVE) {
            return Mono.error(
                new IllegalStateException("An action that is still open cannot be dismissed"));
          }
          return receipt(
              recipient,
              id,
              current ->
                  new Receipt(
                      id,
                      recipient,
                      current.readAt() == null ? now : current.readAt(),
                      current.dismissedAt() == null ? now : current.dismissedAt()));
        })
        .then();
  }

  private Mono<Receipt> receipt(
      final String recipient, final String id, final UnaryOperator<Receipt> change) {
    return store
        .receipts(recipient, List.of(id))
        .next()
        .defaultIfEmpty(new Receipt(id, recipient, null, null))
        .map(change)
        .flatMap(saved -> store.saveReceipt(saved).thenReturn(saved))
        .doOnNext(
            saved -> publish(new NotificationChange(NotificationChange.Kind.RECEIPT, null, saved)));
  }

  /**
   * The notifications a caller may see, newest change first.
   *
   * @param query the filter
   * @param caller the resolved key; {@code null} on an open server
   * @return the visible notifications
   */
  public Flux<Notification> visible(
      final NotificationQuery query, final ApiKeys.@Nullable Match caller) {
    return store.find(query).filter(n -> Recipients.sees(n, caller));
  }

  /**
   * The notification of a key.
   *
   * @param key the dedupe key
   * @return the notification, or empty
   */
  public Mono<Notification> byKey(final String key) {
    return store.findByKey(key);
  }

  /**
   * How many notifications are active, by kind.
   *
   * @return the count per kind; a kind with none is missing
   */
  public Mono<Map<NotificationKind, Long>> activeCounts() {
    return store
        .find(new NotificationQuery(Notification.State.ACTIVE, null, null, 0))
        .collect(Collectors.groupingBy(Notification::kind, Collectors.counting()));
  }

  /**
   * The receipts of a person.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @param ids the notification ids
   * @return the receipts that exist; a notification without one is unread
   */
  public Flux<Receipt> receipts(final String recipient, final Collection<String> ids) {
    return store.receipts(recipient, ids);
  }

  /**
   * The preferences of a person.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @return the preferences, or the defaults
   */
  public Mono<NotificationPreferences> preferences(final String recipient) {
    return store.preferences(recipient);
  }

  /**
   * Saves the preferences of a person.
   *
   * @param preferences the preferences
   * @return completes when saved
   */
  public Mono<Void> savePreferences(final NotificationPreferences preferences) {
    return store.savePreferences(preferences);
  }

  /**
   * Every change from now on. A slow reader misses changes and catches up with {@link #visible} and
   * the sequence.
   *
   * @return the changes
   */
  public Flux<NotificationChange> changes() {
    return changeSink.asFlux();
  }

  /**
   * Names the life of the change sequence.
   *
   * @return the store's epoch
   */
  public String epoch() {
    return store.epoch();
  }

  private void publish(final NotificationChange change) {
    SinkEmit.offer(changeSink, change);
  }
}
