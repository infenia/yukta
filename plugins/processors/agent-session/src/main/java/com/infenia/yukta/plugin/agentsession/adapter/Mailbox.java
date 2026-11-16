// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

/**
 * Holds the messages sent to an agent while it works on a turn, in the order they arrived. The
 * agent takes the next one when the turn ends.
 */
final class Mailbox {

  private final Queue<Waiting> waiting = new ConcurrentLinkedQueue<>();

  /** A message that waits. */
  /* default */ record Waiting(String text, String actor) {}

  /**
   * Adds a message.
   *
   * @param text the message
   * @param actor who sent it
   * @return how many messages wait now
   */
  /* default */ int add(final String text, final String actor) {
    waiting.add(new Waiting(text, actor));
    return waiting.size();
  }

  /**
   * Takes the oldest message.
   *
   * @return the message, or null when none waits
   */
  /* default */ @Nullable Waiting next() {
    return waiting.poll();
  }

  /**
   * How many messages wait.
   *
   * @return the count
   */
  /* default */ int size() {
    return waiting.size();
  }
}
