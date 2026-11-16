// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import reactor.core.publisher.Mono;

/**
 * Sends a notification out of Yukta, such as to a webhook, a chat or an e-mail. Register an
 * implementation as a Spring bean. It replaces {@code InputResponder}: the same channel now carries
 * a question with an answer token and a plain alert.
 *
 * <p>An answer does not come back through this interface: the receiver answers with the one-time
 * token at the token-answer endpoint, so it passes the same review and audit as any other answer.
 *
 * <p>Delivery is at least once, so an implementation must be idempotent by notification id.
 *
 * @since 0.1.0-alpha.5
 */
public interface NotificationChannel {

  /**
   * The name a selector or a rule uses to pick this channel, as in {@code external:<type>} or
   * {@code <type>:<name>}.
   *
   * @return the channel type
   */
  String type();

  /**
   * Delivers the notification, already redacted.
   *
   * @param notification the notification as it is now
   * @param context the target, its settings and, for a question, the request and the answer token
   * @return completes when the receiver has it; an error is retried with backoff
   */
  Mono<Void> deliver(Notification notification, ChannelContext context);
}
