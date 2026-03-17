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
package com.infenia.yukta.service.control;

import com.infenia.yukta.plugin.message.Message;

/**
 * Handler for a specific type of control signal.
 *
 * <p>Implementations process control signals of a particular type (e.g., heartbeat, statistics).
 * Multiple handlers can be registered in ControlBusService; when a signal arrives, the first
 * handler that can handle it processes it.
 */
public interface ControlSignalHandler {

  /**
   * Check whether this handler can process the given payload.
   *
   * @param payload the signal payload
   * @return true if this handler should process the signal, false otherwise
   */
  boolean canHandle(Object payload);

  /**
   * Process a control signal.
   *
   * @param nodeId the source node identifier
   * @param message the full message (includes metadata)
   * @param payload the signal payload (pre-cast by the handler)
   */
  void handle(String nodeId, Message<?> message, Object payload);

  /**
   * Get the last heartbeat for a node (optional, default returns null).
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null if not applicable
   */
  default Message<?> getLastHeartbeat(final String nodeId) {
    return null;
  }

  /**
   * Get the last statistics for a node (optional, default returns null).
   *
   * @param nodeId the node identifier
   * @return the last statistics message, or null if not applicable
   */
  default Message<?> getLastStatistics(final String nodeId) {
    return null;
  }

  /**
   * Get all active node IDs (optional, default returns empty list).
   *
   * @return list of node IDs, or empty list if not applicable
   */
  default java.util.List<String> getActiveNodes() {
    return java.util.List.of();
  }

  /**
   * Remove a node's state (optional, default is no-op).
   *
   * @param nodeId the node identifier
   */
  default void removeNode(final String nodeId) {
    // No-op by default
  }
}
