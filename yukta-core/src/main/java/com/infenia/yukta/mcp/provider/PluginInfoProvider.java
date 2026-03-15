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
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.model.api.PluginCreationGuide;
import com.infenia.yukta.model.api.PluginDetails;
import com.infenia.yukta.model.api.PluginSummary;
import java.util.List;

/**
 * Provider for plugin registry and documentation operations. Handles plugin discovery, details
 * retrieval, and creation guides.
 */
public interface PluginInfoProvider {

  /**
   * List all available plugins.
   *
   * @return list of plugin summaries
   */
  List<PluginSummary> listPlugins();

  /**
   * Get full details of a specific plugin.
   *
   * @param type the plugin type
   * @return plugin details
   */
  PluginDetails getPluginDetails(String type);

  /**
   * Get comprehensive guide for creating plugins.
   *
   * @param templateType optional filter ("trigger", "processor", "terminal", "all")
   * @return plugin creation guide
   */
  PluginCreationGuide getPluginCreationGuide(String templateType);
}
