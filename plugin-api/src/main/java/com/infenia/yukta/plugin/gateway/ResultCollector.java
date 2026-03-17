/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin.gateway;

import com.infenia.yukta.plugin.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collector for harvesting results from terminal nodes. Used for sub-workflow execution to capture
 * final messages.
 */
public class ResultCollector {
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
