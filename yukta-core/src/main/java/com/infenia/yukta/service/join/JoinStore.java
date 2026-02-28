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
package com.infenia.yukta.service.join;

import com.infenia.yukta.plugin.Message;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Interface for managing state of join operations. */
public interface JoinStore {

  /**
   * Add a message to the join state and check if the join condition is met.
   *
   * @param key the unique key for the join operation (sessionId + nodeId + correlationId)
   * @param sourceNodeId the ID of the node that sent the message
   * @param message the message to add
   * @param config the join configuration
   * @return a Mono containing the join result
   */
  Mono<JoinResult> addMessage(
      String key, String sourceNodeId, Message<?> message, JoinConfig config);

  /**
   * Mark a join as completed to handle late arrivals.
   *
   * @param key the unique key
   * @param timeoutMs the timeout after which the completed state can be purged
   */
  void markCompleted(String key, long timeoutMs);

  /** Join configuration parameters. */
  record JoinConfig(
      String mode,
      List<String> expectedAncestors,
      long timeoutMs,
      int customCount,
      int maxPendingJoins) {
    /** Compact constructor. */
    public JoinConfig {
      expectedAncestors = List.copyOf(expectedAncestors != null ? expectedAncestors : List.of());
    }
  }

  /** Result of adding a message to the join store. */
  record JoinResult(Status status, Map<String, Message<?>> collectedMessages) {
    /** Compact constructor. */
    public JoinResult {
      collectedMessages = Map.copyOf(collectedMessages != null ? collectedMessages : Map.of());
    }

    /** Status of the join operation. */
    public enum Status {
      WAITING,
      COMPLETED,
      LATE_ARRIVAL,
      OVERFLOW
    }
  }
}
