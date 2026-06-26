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
