// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.delivery;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One delivery of a request to an external responder of a tier, kept until it is sent or given up.
 * Deliveries are at least once, so a responder must be idempotent by request id.
 *
 * @param id the delivery id
 * @param requestId the request to deliver
 * @param tier the tier that targets the responder
 * @param responder the selector, such as {@code external:webhook:ops}
 * @param state where the delivery is
 * @param attempts how many times it was tried
 * @param nextAttempt when it is tried next
 * @param lastError why the last try failed, or {@code null}
 * @since 0.1.0-alpha.4
 */
public record InputDelivery(
    String id,
    String requestId,
    String tier,
    String responder,
    State state,
    int attempts,
    Instant nextAttempt,
    @Nullable String lastError) {

  /** Where a delivery is. */
  public enum State {
    /** Waiting to be sent or sent again. */
    PENDING,
    /** The receiver has the request. */
    SENT,
    /** Given up after too many tries. */
    FAILED
  }

  /**
   * Checks the parts.
   *
   * @param id the delivery id
   * @param requestId the request
   * @param tier the tier
   * @param responder the selector
   * @param state the state
   * @param attempts the tries so far
   * @param nextAttempt when it is tried next
   * @param lastError the last error, or {@code null}
   */
  public InputDelivery {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(responder, "responder");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(nextAttempt, "nextAttempt");
  }

  /**
   * The delivery after a failed try.
   *
   * @param error why it failed
   * @param next when to try again
   * @param giveUp whether to stop trying
   * @return the delivery with one more attempt
   */
  public InputDelivery failed(final String error, final Instant next, final boolean giveUp) {
    return new InputDelivery(
        id,
        requestId,
        tier,
        responder,
        giveUp ? State.FAILED : State.PENDING,
        attempts + 1,
        next,
        error);
  }

  /**
   * The delivery once the receiver has the request.
   *
   * @return the sent delivery
   */
  public InputDelivery sent() {
    return new InputDelivery(
        id, requestId, tier, responder, State.SENT, attempts + 1, nextAttempt, null);
  }
}
