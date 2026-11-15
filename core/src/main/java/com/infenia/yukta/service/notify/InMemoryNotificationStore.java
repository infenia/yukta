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
public final class InMemoryNotificationStore implements NotificationStore {

  /** Guards every map and the sequence. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The notifications by key, oldest first. */
  private final Map<String, Notification> byKey = new LinkedHashMap<>();

  /** The receipts by recipient, then notification id. */
  private final Map<String, Map<String, Receipt>> receipts = new HashMap<>();

  /** The preferences by recipient. */
  private final Map<String, NotificationPreferences> preferences = new HashMap<>();

  /** Most notifications kept. */
  private final int maxItems;

  /** Names this life of the sequence. */
  private final String epoch = UUID.randomUUID().toString();

  /** The last sequence handed out. */
  private long seq;

  /**
   * Creates the store.
   *
   * @param maxItems most notifications kept; at least 1
   */
  public InMemoryNotificationStore(final int maxItems) {
    if (maxItems < 1) {
      throw new IllegalArgumentException("maxItems must be at least 1");
    }
    this.maxItems = maxItems;
  }

  @Override
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
          final Notification stored = merged.stored(id, ++seq);
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
          final Notification stored = current.resolved(resolution, ++seq, at);
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
    return Flux.defer(() ->
        Flux.fromIterable(
            locked(() ->
                byKey.values().stream()
                    .filter(n -> query.state() == null || n.state() == query.state())
                    .filter(n -> query.kind() == null || n.kind() == query.kind())
                    .filter(
                        n -> query.sessionId() == null || query.sessionId().equals(n.sessionId()))
                    .filter(n -> n.seq() > query.sinceSeq())
                    .sorted(Comparator.comparingLong(Notification::seq).reversed())
                    .toList())));
  }

  @Override
  public Mono<Void> saveReceipt(final Receipt receipt) {
    return Mono.fromRunnable(() ->
        locked(() ->
            receipts
                .computeIfAbsent(receipt.recipient(), r -> new HashMap<>())
                .put(receipt.notificationId(), receipt)));
  }

  @Override
  public Flux<Receipt> receipts(final String recipient, final Collection<String> ids) {
    return Flux.defer(() ->
        Flux.fromIterable(
            locked(() -> {
              final Map<String, Receipt> mine = receipts.getOrDefault(recipient, Map.of());
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
            preferences.getOrDefault(recipient, NotificationPreferences.defaults(recipient))));
  }

  @Override
  public Mono<Void> savePreferences(final NotificationPreferences value) {
    return Mono.fromRunnable(() -> locked(() -> preferences.put(value.recipient(), value)));
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
  public String epoch() {
    return epoch;
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

  private void forget(final Notification n) {
    receipts.values().forEach(mine -> mine.remove(n.id()));
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
