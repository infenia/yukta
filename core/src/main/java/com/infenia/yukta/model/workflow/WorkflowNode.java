// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import java.util.Map;

/**
 * Internal representation of a workflow node for execution.
 *
 * @param nodeId unique identifier for the node
 * @param type the type of plugin to use
 * @param config configuration for the plugin
 */
public record WorkflowNode(String nodeId, String type, Map<String, Object> config) {

  /** Compact constructor. */
  public WorkflowNode {
    config = config != null ? Map.copyOf(config) : Map.of();
  }
}
