// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.ControlStatistics;
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

  /** Map of composite key to last statistics message. */
  private final Map<String, Message<?>> lastStatistics = new ConcurrentHashMap<>();

  @Override
  public boolean canHandle(final Object payload) {
    return payload instanceof ControlStatistics;
  }

  @Override
  public void handle(final String compositeKey, final Message<?> message, final Object payload) {
    lastStatistics.put(compositeKey, message);
  }

  /**
   * Get the last statistics message for a node.
   *
   * @param compositeKey the composite key (workflowId + "\0" + nodeId)
   * @return the last statistics message, or null if none
   */
  @Override
  public Message<?> getLastStatistics(final String compositeKey) {
    return lastStatistics.get(compositeKey);
  }

  /**
   * Remove a node's statistics record.
   *
   * @param compositeKey the composite key (workflowId + "\0" + nodeId)
   */
  @Override
  public void removeNode(final String compositeKey) {
    lastStatistics.remove(compositeKey);
  }
}
