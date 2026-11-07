// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import com.infenia.yukta.plugin.core.Plugin;
import java.util.Optional;

/** Lets a plugin find another plugin by its type, for plugins that run a configured target. */
@FunctionalInterface
public interface PluginLookup {

  /**
   * Finds a plugin.
   *
   * @param type the plugin type
   * @return the plugin, or empty if the type is null or unknown
   */
  Optional<Plugin> find(String type);
}
