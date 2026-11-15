// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.notify.Notification;
import java.time.Clock;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Queues the deliveries of a notification to outbound channels. Two things queue one: a route tier
 * that targets {@code external:<type>[:<name>]} (a delivery with an answer token) and a rule that
 * lists {@code channels} (a delivery without one). Queueing the same thing twice changes nothing,
 * so a repair can run any time.
 */
@Component
public class NotificationOutbox {

  /** The prefix of a tier target that names a channel. */
  private static final String EXTERNAL = "external:";

  private final NotificationStore store;
  private final Clock clock;

  /**
   * Creates the outbox.
   *
   * @param store where the deliveries are kept
   */
  @Autowired
  public NotificationOutbox(final NotificationStore store) {
    this(store, Clock.systemUTC());
  }

  /**
   * Creates the outbox with an explicit clock.
   *
   * @param store where the deliveries are kept
   * @param clock the time a delivery is due
   */
  public NotificationOutbox(final NotificationStore store, final Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * Whether a tier target names a channel.
   *
   * @param target a selector of a tier
   * @return true for {@code external:...}
   */
  public static boolean isExternal(final String target) {
    return target.startsWith(EXTERNAL);
  }

  /**
   * Queues a delivery with an answer token for each channel the current tier of a request names.
   *
   * @param notification the action of the request
   * @param request the request, in the tier whose targets are read
   * @return completes when queued
   */
  public Mono<Void> enqueueTier(final Notification notification, final InputRequest request) {
    return Flux.fromIterable(request.targets())
        .filter(NotificationOutbox::isExternal)
        .concatMap(target ->
            store.enqueueDelivery(
                NotificationDelivery.pending(
                    notification.id(),
                    target.substring(EXTERNAL.length()),
                    request.tier(),
                    clock.instant())))
        .then();
  }

  /**
   * Queues a delivery without a token for each channel of a rule.
   *
   * @param notification the notification
   * @param channels the channels as {@code <type>:<name>}
   * @return completes when queued
   */
  public Mono<Void> enqueueAll(final Notification notification, final Collection<String> channels) {
    return Flux.fromIterable(channels)
        .concatMap(channel ->
            store.enqueueDelivery(
                NotificationDelivery.pending(notification.id(), channel, null, clock.instant())))
        .then();
  }
}
