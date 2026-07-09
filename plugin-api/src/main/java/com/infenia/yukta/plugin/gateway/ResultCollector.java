// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collector for harvesting results from terminal nodes. Used for sub-workflow execution to capture
 * final messages.
 */
public class ResultCollector {
  /** Thread-safe queue for storing collected result messages. */
  private final Queue<Message<?>> results = new ConcurrentLinkedQueue<>();

  /** Default constructor. */
  public ResultCollector() {
    super();
  }

  /**
   * Add a message to the collector.
   *
   * @param message the message to add
   */
  public void add(final Message<?> message) {
    if (message != null) {
      results.add(message);
    }
  }

  /**
   * Get all collected messages.
   *
   * @return list of collected messages
   */
  public List<Message<?>> getResults() {
    return new ArrayList<>(results);
  }

  /** Clear all collected messages. */
  public void clear() {
    results.clear();
  }
}
