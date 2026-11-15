// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.Notification;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where notifications, receipts and preferences are kept. In memory in Community, PostgreSQL in
 * Plus. Every change of a notification advances one sequence; the store assigns it.
 */
@SuppressWarnings("PMD.TooManyMethods")
public interface NotificationStore {

  /**
   * Creates or changes the notification of a key, atomically. {@code merge} gets the current one
   * and returns what to keep. Returning the same instance it was given changes nothing and does not
   * advance the sequence. A new notification gets an id; every change gets the next sequence.
   *
   * @param key the dedupe key
   * @param merge decides the new state from the current one
   * @return the notification as stored
   */
  Mono<Notification> upsert(String key, Function<Optional<Notification>, Notification> merge);

  /**
   * Ends the notification of a key.
   *
   * @param key the dedupe key
   * @param resolution how it ended
   * @param at when
   * @return the resolved notification; empty if there is none or it was already resolved
   */
  Mono<Notification> resolve(String key, String resolution, Instant at);

  /**
   * Finds one by key.
   *
   * @param key the dedupe key
   * @return the notification, or empty
   */
  Mono<Notification> findByKey(String key);

  /**
   * Finds one by id.
   *
   * @param id the id
   * @return the notification, or empty
   */
  Mono<Notification> findById(String id);

  /**
   * Finds notifications, newest change first.
   *
   * @param query the filter
   * @return the matches
   */
  Flux<Notification> find(NotificationQuery query);

  /**
   * Saves what one person did with a notification, replacing the earlier receipt.
   *
   * @param receipt the receipt
   * @return completes when saved
   */
  Mono<Void> saveReceipt(Receipt receipt);

  /**
   * The receipts of a person for some notifications.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @param ids the notification ids
   * @return the receipts that exist
   */
  Flux<Receipt> receipts(String recipient, Collection<String> ids);

  /**
   * The preferences of a person.
   *
   * @param recipient the key name, or {@link Recipients#LOCAL}
   * @return the saved preferences, or the defaults
   */
  Mono<NotificationPreferences> preferences(String recipient);

  /**
   * Saves the preferences of a person.
   *
   * @param preferences the preferences
   * @return completes when saved
   */
  Mono<Void> savePreferences(NotificationPreferences preferences);

  /**
   * Removes resolved actions and info notifications not changed since a time, with their receipts.
   * An active action stays.
   *
   * @param before the cut-off
   * @return how many were removed
   */
  Mono<Long> purge(Instant before);

  /**
   * Names the life of the sequence: it changes when the sequence restarts (an in-memory store after
   * a restart), so a client holding an old position knows to load the list again.
   *
   * @return the epoch
   */
  String epoch();
}
