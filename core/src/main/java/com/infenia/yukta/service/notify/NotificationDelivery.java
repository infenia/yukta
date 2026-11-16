// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One delivery of a notification to an outbound channel, kept until it is sent or given up.
 * Deliveries are at least once, so a channel must be idempotent by notification id.
 *
 * @param id the delivery id; the same notification, channel and tier always give the same id, so a
 *     second enqueue changes nothing
 * @param notificationId the notification to deliver
 * @param channel the target as {@code <type>:<name>}, such as {@code webhook:ops}
 * @param tier the input tier that names the channel as a responder, or {@code null} for a rule's
 *     channel; only a tier delivery carries an answer token
 * @param state where the delivery is
 * @param attempts how many times it was tried
 * @param nextAttempt when it is tried next
 * @param lastError why the last try failed, or {@code null}
 */
public record NotificationDelivery(
    String id,
    String notificationId,
    String channel,
    @Nullable String tier,
    State state,
    int attempts,
    Instant nextAttempt,
    @Nullable String lastError) {

  /** Where a delivery is. */
  public enum State {
    /** Waiting to be sent or sent again. */
    PENDING,
    /** The receiver has it. */
    SENT,
    /** Given up after too many tries. */
    FAILED
  }

  /** Checks the required parts. */
  public NotificationDelivery {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(notificationId, "notificationId");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(nextAttempt, "nextAttempt");
  }

  /**
   * A new pending delivery that is due now.
   *
   * @param notificationId the notification
   * @param channel the target as {@code <type>:<name>}
   * @param tier the input tier for a tier delivery, or {@code null}
   * @param now when it is due
   * @return the delivery
   */
  public static NotificationDelivery pending(
      final String notificationId,
      final String channel,
      final @Nullable String tier,
      final Instant now) {
    return new NotificationDelivery(
        notificationId + "|" + channel + "|" + (tier == null ? "" : tier),
        notificationId,
        channel,
        tier,
        State.PENDING,
        0,
        now,
        null);
  }

  /**
   * The delivery after a failed try.
   *
   * @param error why it failed
   * @param next when to try again
   * @param giveUp whether to stop trying
   * @return the delivery with one more attempt
   */
  public NotificationDelivery failed(final String error, final Instant next, final boolean giveUp) {
    return new NotificationDelivery(
        id,
        notificationId,
        channel,
        tier,
        giveUp ? State.FAILED : State.PENDING,
        attempts + 1,
        next,
        error);
  }

  /**
   * The delivery once the receiver has it.
   *
   * @return the sent delivery
   */
  public NotificationDelivery sent() {
    return new NotificationDelivery(
        id, notificationId, channel, tier, State.SENT, attempts + 1, nextAttempt, null);
  }
}
