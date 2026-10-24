// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collector for harvesting results from terminal nodes. Used for sub-workflow execution to capture
 * final messages, and how the run ended at each terminal it reached.
 */
public class ResultCollector {
  /** Thread-safe queue for storing collected result messages. */
  private final Queue<Message<?>> results = new ConcurrentLinkedQueue<>();

  /** The terminals the run reached, in the order they finished. */
  private final Queue<Completion> completions = new ConcurrentLinkedQueue<>();

  /**
   * A terminal the run reached, with its completion metadata (for an outcome terminal: {@code
   * outcome}, and {@code reason} or {@code restartFrom} when configured).
   *
   * @param nodeId the terminal node
   * @param metadata what the terminal reported when it completed
   */
  public record Completion(String nodeId, Map<String, Object> metadata) {

    /** Copies the metadata. */
    public Completion {
      metadata = Map.copyOf(metadata);
    }
  }

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

  /**
   * Record that a terminal was reached and how the run ended there.
   *
   * @param nodeId the terminal node
   * @param metadata its completion metadata
   */
  public void addCompletion(final String nodeId, final Map<String, Object> metadata) {
    completions.add(new Completion(nodeId, metadata));
  }

  /**
   * Get the terminals the run reached.
   *
   * @return the completions, in the order the terminals finished
   */
  public List<Completion> getCompletions() {
    return new ArrayList<>(completions);
  }

  /** Clear all collected messages and completions. */
  public void clear() {
    results.clear();
    completions.clear();
  }
}
