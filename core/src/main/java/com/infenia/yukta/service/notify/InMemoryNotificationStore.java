// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps notifications in memory; they are gone after a restart, which changes the {@link #epoch()}.
 * At most {@code maxItems} are kept: the oldest resolved action or info notification goes first,
 * and an active action is never evicted, so the bound can be exceeded by active actions.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects", "PMD.UseConcurrentHashMap"})
public final class InMemoryNotificationStore implements NotificationStore {

  /** The smallest bound accepted. */
  private static final int MIN_ITEMS = 1;

  /** Guards every map and the sequence. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The notifications by key, oldest first. */
  private final Map<String, Notification> byKey = new LinkedHashMap<>();

  /** The receipts by recipient, then notification id. */
  private final Map<String, Map<String, Receipt>> receiptsByRecipient = new HashMap<>();

  /** The preferences by recipient. */
  private final Map<String, NotificationPreferences> preferencesByRecipient = new HashMap<>();

  /** The deliveries by id. */
  private final Map<String, NotificationDelivery> deliveries = new HashMap<>();

  /** Most notifications kept. */
  private final int maxItems;

  /** Names this life of the sequence. */
  private final String epochId = UUID.randomUUID().toString();

  /** The last sequence handed out. */
  private long seq;

  /**
   * Creates the store.
   *
   * @param maxItems most notifications kept; at least 1
   */
  public InMemoryNotificationStore(final int maxItems) {
    if (maxItems < MIN_ITEMS) {
      throw new IllegalArgumentException("maxItems must be at least 1");
    }
    this.maxItems = maxItems;
  }

  @Override
  @SuppressWarnings("PMD.CompareObjectsWithEquals")
  public Mono<Notification> upsert(
      final String key, final Function<Optional<Notification>, Notification> merge) {
    return Mono.fromSupplier(() ->
        locked(() -> {
          final Notification current = byKey.get(key);
          final Notification merged = merge.apply(Optional.ofNullable(current));
          if (merged == current) {
            return current;
          }
          final String id = current == null ? UUID.randomUUID().toString() : current.id();
          final Notification stored = merged.stored(id, nextSeq());
          byKey.put(key, stored);
          evict();
          return stored;
        }));
  }

  @Override
  public Mono<Notification> resolve(final String key, final String resolution, final Instant at) {
    return Mono.fromSupplier(() ->
        locked(() -> {
          final Notification current = byKey.get(key);
          if (current == null || current.state() == Notification.State.RESOLVED) {
            return null;
          }
          final Notification stored = current.resolved(resolution, nextSeq(), at);
          byKey.put(key, stored);
          return stored;
        }));
  }

  @Override
  public Mono<Notification> findByKey(final String key) {
    return Mono.fromSupplier(() -> locked(() -> byKey.get(key)));
  }

  @Override
  public Mono<Notification> findById(final String id) {
    return Mono.fromSupplier(() ->
        locked(
            () -> byKey.values().stream().filter(n -> n.id().equals(id)).findFirst().orElse(null)));
  }

  @Override
  public Flux<Notification> find(final NotificationQuery query) {
    final String session = query.sessionId();
    return Flux.defer(() ->
        Flux.fromIterable(
            locked(() ->
                byKey.values().stream()
                    .filter(n -> query.state() == null || n.state() == query.state())
                    .filter(n -> query.kind() == null || n.kind() == query.kind())
                    .filter(n -> session == null || session.equals(n.sessionId()))
                    .filter(n -> n.seq() > query.sinceSeq())
                    .sorted(Comparator.comparingLong(Notification::seq).reversed())
                    .toList())));
  }

  @Override
  public Mono<Void> saveReceipt(final Receipt receipt) {
    // a receipt for a notification that is gone is not kept
    return Mono.fromRunnable(() ->
        locked(() -> {
          if (byKey.values().stream().anyMatch(n -> n.id().equals(receipt.notificationId()))) {
            receiptsByRecipient
                .computeIfAbsent(receipt.recipient(), r -> new HashMap<>())
                .put(receipt.notificationId(), receipt);
          }
          return null;
        }));
  }

  @Override
  public Flux<Receipt> receipts(final String recipient, final Collection<String> ids) {
    return Flux.defer(() ->
        Flux.fromIterable(
            locked(() -> {
              final Map<String, Receipt> mine =
                  receiptsByRecipient.getOrDefault(recipient, Map.of());
              final List<Receipt> found = new ArrayList<>();
              for (final String id : ids) {
                final Receipt receipt = mine.get(id);
                if (receipt != null) {
                  found.add(receipt);
                }
              }
              return found;
            })));
  }

  @Override
  public Mono<NotificationPreferences> preferences(final String recipient) {
    return Mono.fromSupplier(() ->
        locked(() ->
            preferencesByRecipient.getOrDefault(
                recipient, NotificationPreferences.defaults(recipient))));
  }

  @Override
  public Mono<Void> savePreferences(final NotificationPreferences value) {
    return Mono.fromRunnable(
        () -> locked(() -> preferencesByRecipient.put(value.recipient(), value)));
  }

  @Override
  public Mono<NotificationDelivery> enqueueDelivery(final NotificationDelivery delivery) {
    return Mono.fromSupplier(() ->
        locked(() -> {
          final NotificationDelivery existing = deliveries.putIfAbsent(delivery.id(), delivery);
          return existing == null ? delivery : existing;
        }));
  }

  @Override
  public Flux<NotificationDelivery> dueDeliveries(final Instant now) {
    return Flux.defer(() ->
        Flux.fromIterable(
            locked(() ->
                deliveries.values().stream()
                    .filter(d -> d.state() == NotificationDelivery.State.PENDING)
                    .filter(d -> !d.nextAttempt().isAfter(now))
                    .sorted(Comparator.comparing(NotificationDelivery::nextAttempt))
                    .toList())));
  }

  @Override
  public Mono<Void> saveDelivery(final NotificationDelivery delivery) {
    // a delivery of a notification that is gone is not kept
    return Mono.fromRunnable(() ->
        locked(() -> {
          if (byKey.values().stream().anyMatch(n -> n.id().equals(delivery.notificationId()))) {
            deliveries.put(delivery.id(), delivery);
          } else {
            deliveries.remove(delivery.id());
          }
          return null;
        }));
  }

  @Override
  public Mono<Long> purge(final Instant before) {
    return Mono.fromSupplier(() ->
        locked(() -> {
          long removed = 0;
          final Iterator<Notification> it = byKey.values().iterator();
          while (it.hasNext()) {
            final Notification n = it.next();
            if (removable(n) && n.updatedAt().isBefore(before)) {
              it.remove();
              forget(n);
              removed++;
            }
          }
          return removed;
        }));
  }

  @Override
  public Mono<String> epoch() {
    return Mono.just(epochId);
  }

  /** Whether the notification may be evicted or purged: not an active action. */
  private static boolean removable(final Notification n) {
    return n.kind() == NotificationKind.INFO || n.state() == Notification.State.RESOLVED;
  }

  /** Drops the oldest removable notifications while over the bound. */
  private void evict() {
    final Iterator<Notification> it = byKey.values().iterator();
    while (byKey.size() > maxItems && it.hasNext()) {
      final Notification n = it.next();
      if (removable(n)) {
        it.remove();
        forget(n);
      }
    }
  }

  private long nextSeq() {
    seq++;
    return seq;
  }

  private void forget(final Notification n) {
    receiptsByRecipient.values().forEach(mine -> mine.remove(n.id()));
    deliveries.values().removeIf(d -> d.notificationId().equals(n.id()));
  }

  private <T> T locked(final Supplier<T> body) {
    lock.lock();
    try {
      return body.get();
    } finally {
      lock.unlock();
    }
  }
}
