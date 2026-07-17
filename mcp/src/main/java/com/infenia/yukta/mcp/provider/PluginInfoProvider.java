// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.PluginCreationGuide;
import com.infenia.yukta.mcp.dto.PluginDetails;
import com.infenia.yukta.mcp.dto.PluginSummary;
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
