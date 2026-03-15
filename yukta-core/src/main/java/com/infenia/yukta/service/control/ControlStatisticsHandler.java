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
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handler for ControlStatistics signals.
 *
 * <p>Stores the last statistics message from each node for performance monitoring.
 */
@Component
@NoArgsConstructor
public class ControlStatisticsHandler implements ControlSignalHandler {

  private final Map<String, Message<?>> lastStatistics = new ConcurrentHashMap<>();

  @Override
  public boolean canHandle(final Object payload) {
    return payload instanceof ControlStatistics;
  }

  @Override
  public void handle(final String nodeId, final Message<?> message, final Object payload) {
    lastStatistics.put(nodeId, message);
  }

  /**
   * Get the last statistics message for a node.
   *
   * @param nodeId the node identifier
   * @return the last statistics message, or null if none
   */
  @Override
  public Message<?> getLastStatistics(final String nodeId) {
    return lastStatistics.get(nodeId);
  }

  /**
   * Remove a node's statistics record.
   *
   * @param nodeId the node identifier
   */
  @Override
  public void removeNode(final String nodeId) {
    lastStatistics.remove(nodeId);
  }
}
