// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import reactor.core.publisher.Mono;

/**
 * Lets a workflow raise a notification in the inbox. A plugin takes it through an {@code
 * ObjectProvider<NotificationGateway>}. The session, workflow, run and node of the notification are
 * read from the Reactor context the engine writes for every node ({@link
 * com.infenia.yukta.plugin.input.InputIdentity}), so a plugin cannot raise one in the name of
 * another run. The category is always {@code workflow}.
 *
 * <p>A run may raise at most {@link #MAX_PER_MINUTE} notifications a minute and {@link
 * #MAX_PER_EXECUTION} in all; beyond that the call fails with {@link NotificationLimitException}.
 *
 * @since 0.1.0-alpha.5
 */
@FunctionalInterface
public interface NotificationGateway {

  /** Most notifications one run may raise in a minute. */
  int MAX_PER_MINUTE = 20;

  /** Most notifications one run may raise. */
  int MAX_PER_EXECUTION = 200;

  /**
   * Raises a notification.
   *
   * @param spec what to raise; its {@code channels} may only name configured channels
   * @return completes when it is stored; fails with {@link NotificationLimitException} beyond the
   *     limits and with {@link IllegalArgumentException} for a channel that is not configured
   */
  Mono<Void> notify(NotificationSpec spec);
}
