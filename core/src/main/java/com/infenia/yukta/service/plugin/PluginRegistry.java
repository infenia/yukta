// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.plugin;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.validation.PluginName;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/** Registry for workflow plugins. */
@Service
@Validated
public class PluginRegistry {
  /** Map of plugin type to plugin instances. */
  private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();

  /**
   * Public constructor.
   *
   * @param pluginList list of plugins discovered by Spring
   */
  public PluginRegistry(@NotEmpty final List<Plugin> pluginList) {
    pluginList.forEach(plugin -> plugins.put(plugin.getType(), plugin));
  }

  /**
   * Get a plugin by its type.
   *
   * @param type the plugin type
   * @return the plugin, or null if not found
   */
  public Plugin get(@PluginName final String type) {
    return plugins.get(type);
  }

  /**
   * Check if a plugin type is registered.
   *
   * @param type the plugin type
   * @return true if registered
   */
  public boolean contains(@PluginName final String type) {
    return plugins.containsKey(type);
  }

  /**
   * Get all registered plugins.
   *
   * @return list of all plugins
   */
  public List<Plugin> listPlugins() {
    return List.copyOf(plugins.values());
  }
}
