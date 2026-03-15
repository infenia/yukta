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
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handler for ControlHeartbeat signals.
 *
 * <p>Stores the last heartbeat message from each node for status queries.
 */
@Component
@NoArgsConstructor
public class ControlHeartbeatHandler implements ControlSignalHandler {

  private final Map<String, Message<?>> lastHeartbeats = new ConcurrentHashMap<>();

  @Override
  public boolean canHandle(final Object payload) {
    return payload instanceof ControlHeartbeat;
  }

  @Override
  public void handle(final String nodeId, final Message<?> message, final Object payload) {
    lastHeartbeats.put(nodeId, message);
  }

  /**
   * Get the last heartbeat for a node.
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null if none
   */
  @Override
  public Message<?> getLastHeartbeat(final String nodeId) {
    return lastHeartbeats.get(nodeId);
  }

  /**
   * Get all active node IDs.
   *
   * @return list of node IDs that have sent heartbeats
   */
  @Override
  public java.util.List<String> getActiveNodes() {
    return java.util.List.copyOf(lastHeartbeats.keySet());
  }

  /**
   * Remove a node's heartbeat record.
   *
   * @param nodeId the node identifier
   */
  @Override
  public void removeNode(final String nodeId) {
    lastHeartbeats.remove(nodeId);
  }
}
