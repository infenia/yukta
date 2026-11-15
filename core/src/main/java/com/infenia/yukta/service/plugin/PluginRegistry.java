// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.plugin;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginApi;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.plugin.gateway.PluginLookup;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Registry for workflow plugins. */
@Slf4j
@Service
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Fails fast at startup on duplicate or incompatible plugin types")
public class PluginRegistry implements PluginLookup {
  /** Plugin type to plugin, sorted by type so listings are stable; filled once, never changed. */
  @SuppressWarnings("PMD.UseConcurrentHashMap") // never changed after construction
  private final Map<String, Plugin> plugins = new TreeMap<>();

  /** Plugins that are on the classpath but not enabled, by type; filled once, never changed. */
  @SuppressWarnings("PMD.UseConcurrentHashMap") // never changed after construction
  private final Map<String, Plugin> disabled = new TreeMap<>();

  @Override
  public Optional<Plugin> find(final String type) {
    return type == null ? Optional.empty() : Optional.ofNullable(plugins.get(type));
  }

  /**
   * Registers every plugin; for tests and callers that want them all.
   *
   * @param pluginList the plugins
   */
  public PluginRegistry(@NotEmpty final List<Plugin> pluginList) {
    this(pluginList, List.of("all"));
  }

  /**
   * Registers the plugins an operator wants: {@link PluginTier#CORE} and {@link
   * PluginTier#EXTENDED} unrestricted ones always, the rest only when named in {@code
   * yukta.plugins.enabled} (a list of types, or {@code all}).
   *
   * @param pluginList the plugins on the classpath
   * @param enabled the types the operator enabled, or {@code all}
   */
  @Autowired
  public PluginRegistry(
      @NotEmpty final List<Plugin> pluginList,
      @Value("${yukta.plugins.enabled:}") final List<String> enabled) {
    final boolean all = enabled.contains("all");
    log.atInfo().log("Plugin SPI {}", PluginApi.VERSION);
    for (final Plugin plugin : pluginList) {
      requireCompatible(plugin);
      if (!all
          && (plugin.getTier() == PluginTier.SPECIFIC || plugin.isRestricted())
          && !enabled.contains(plugin.getType())) {
        disabled.put(plugin.getType(), plugin);
        continue;
      }
      final Plugin previous = plugins.putIfAbsent(plugin.getType(), plugin);
      if (previous != null) {
        throw new IllegalStateException(
            "Duplicate plugin type '"
                + plugin.getType()
                + "': "
                + previous.getClass().getName()
                + " and "
                + plugin.getClass().getName());
      }
    }
  }

  /** Fails startup, naming the plugin, when it was built for an SPI this server cannot run. */
  private static void requireCompatible(final Plugin plugin) {
    final String incompatible = PluginApi.refusal(plugin.apiVersion());
    if (incompatible != null) {
      throw new IllegalStateException(
          "Plugin '"
              + plugin.getType()
              + "' ("
              + plugin.getClass().getName()
              + ") cannot run: "
              + incompatible);
    }
  }

  /**
   * Get a plugin by its type.
   *
   * @param type the plugin type
   * @return the plugin, or null if not found
   */
  public Plugin get(final String type) {
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

  /**
   * Why a type is not available.
   *
   * @param type the plugin type
   * @return a hint naming the property when the plugin is on the classpath but disabled
   */
  public String missingHint(final String type) {
    return disabled.containsKey(type) ? " (disabled; " + enableHint(type) + ")" : "";
  }

  /**
   * How an operator enables a disabled plugin, for clients that cannot read the docs.
   *
   * @param type the plugin type
   * @return the setting to add
   */
  public String enableHint(final String type) {
    return "set yukta.plugins.enabled to include "
        + type
        + " (env YUKTA_PLUGINS_ENABLED="
        + type
        + ", or all) and restart";
  }

  /**
   * Plugins on the classpath that are not enabled.
   *
   * @return the disabled plugins
   */
  public List<Plugin> listDisabled() {
    return List.copyOf(disabled.values());
  }

  /**
   * Finds a plugin that is on the classpath but not enabled.
   *
   * @param type the plugin type
   * @return the plugin, or null
   */
  public Plugin getDisabled(final String type) {
    return disabled.get(type);
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
