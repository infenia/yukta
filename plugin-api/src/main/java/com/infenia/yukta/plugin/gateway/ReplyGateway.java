// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

/**
 * Answers a caller that is waiting for a run: a webhook or API start called with {@code ?wait=}.
 * The waiting call's reply key reaches the trigger in the start payload under {@link
 * #REPLY_TO_KEY}; the trigger puts it on its message as {@code replyTo}, every message derived from
 * it carries it on, and the {@code REPLY} terminal answers with its payload.
 */
@FunctionalInterface
public interface ReplyGateway {

  /** Start-payload key that holds the reply key; set by the server, never by a caller. */
  String REPLY_TO_KEY = "yukta.replyTo";

  /**
   * Answers the caller waiting on the key. The first answer wins.
   *
   * @param key the reply key ({@code Message.replyTo()})
   * @param payload the answer
   * @return true if a caller was waiting and got this answer
   */
  boolean reply(String key, Object payload);
}
