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
package com.infenia.jagratha.service;

import com.infenia.jagratha.plugin.WorkflowPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Registry for workflow plugins. */
@Service
public class WorkflowRegistry {
  private final Map<String, WorkflowPlugin> plugins = new ConcurrentHashMap<>();

  /**
   * Public constructor.
   *
   * @param pluginList list of plugins discovered by Spring
   */
  public WorkflowRegistry(final List<WorkflowPlugin> pluginList) {
    pluginList.forEach(plugin -> plugins.put(plugin.getType(), plugin));
  }

  /**
   * Get a plugin by its type.
   *
   * @param type the plugin type
   * @return the plugin, or null if not found
   */
  public WorkflowPlugin get(final String type) {
    return plugins.get(type);
  }

  /**
   * Check if a plugin type is registered.
   *
   * @param type the plugin type
   * @return true if registered
   */
  public boolean contains(final String type) {
    return plugins.containsKey(type);
  }
}
