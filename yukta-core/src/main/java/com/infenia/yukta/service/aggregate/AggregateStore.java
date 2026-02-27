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
package com.infenia.yukta.service.aggregate;

import com.infenia.yukta.plugin.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Interface for managing state of aggregation operations. */
public interface AggregateStore {

  /**
   * Add a value to the aggregate state and check if the trigger condition is met.
   *
   * @param key the unique key for the aggregation (sessionId + nodeId + groupByValue)
   * @param value the value to add
   * @param originalMessage the original message (for context)
   * @param config the aggregate configuration
   * @return a Mono containing the aggregate result
   */
  Mono<AggregateResult> addValue(
      String key, Object value, Message originalMessage, AggregateConfig config);

  /**
   * Manually flush an aggregate window.
   *
   * @param key the unique key
   * @return a Mono containing the aggregate result
   */
  Mono<AggregateResult> flush(String key);

  /**
   * Flush all active windows (e.g., during shutdown).
   *
   * @return a Flux of all remaining aggregate results
   */
  Flux<AggregateResult> flushAll();

  /**
   * Flush all active windows matching a key prefix.
   *
   * @param keyPrefix the prefix to match
   * @return a Flux of remaining aggregate results
   */
  Flux<AggregateResult> flushAll(String keyPrefix);

  /**
   * Get a flux of results that are triggered by background timers or eviction.
   *
   * @return a Flux of aggregate results
   */
  Flux<AggregateResult> getAsyncResults();

  /** Aggregate configuration parameters. */
  record AggregateConfig(
      String windowType,
      int windowSize,
      long durationMs,
      String aggregationType,
      int maxPendingWindows,
      boolean emitOnTimeout,
      String nullPolicy,
      Object initVal,
      String accExp,
      String resExp) {}

  /** Result of an aggregation operation. */
  record AggregateResult(Status status, Object result, String key, Message lastMessage) {

    /** Status of the aggregation operation. */
    public enum Status {
      WAITING,
      COMPLETED,
      EVICTED
    }
  }
}
