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
package com.infenia.yukta.service.resequence;

import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Interface for managing state of resequencing operations. */
public interface ResequencerStore {

  /**
   * Add a message to the resequencer buffer and return any consecutive messages.
   *
   * @param key the unique key for the sequence (sessionId + nodeId + sequenceId)
   * @param sequenceNumber the sequence number of the message
   * @param message the message to add
   * @param config the resequencer configuration
   * @return a Mono containing the resequencing result
   */
  Mono<ResequenceResult> addMessage(
      String key, long sequenceNumber, Message<?> message, ResequenceConfig config);

  /**
   * Get a flux of results that are triggered by background timers (gap timeouts).
   *
   * @return a Flux of resequencing results
   */
  Flux<ResequenceResult> getAsyncResults();

  /**
   * Flush all active sequences matching a key prefix.
   *
   * @param keyPrefix the prefix to match
   * @return a Flux of remaining resequencing results
   */
  Flux<ResequenceResult> flushAll(String keyPrefix);

  /** Resequencer configuration parameters. */
  record ResequenceConfig(long startIndex, long timeoutMs, int maxPending, String errorPort) {}

  /** Result of adding a message or a timeout event. */
  record ResequenceResult(
      Status status, List<Message<?>> messages, String key, String failureReason) {
    /** Compact constructor. */
    public ResequenceResult {
      messages = List.copyOf(messages != null ? messages : List.of());
    }

    /** Status of the resequencing operation result. */
    public enum Status {
      COMPLETED,
      WAITING,
      TIMEOUT_JUMP,
      LATE_ARRIVAL,
      DUPLICATE,
      OVERFLOW
    }
  }
}
