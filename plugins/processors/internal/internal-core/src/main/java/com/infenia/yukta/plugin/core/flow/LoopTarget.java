// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

/**
 * Finds the processor a loop node runs, so the loop can pass the lifecycle calls ({@code
 * validateConfig}, {@code initialize}, {@code prepare}) on to it with the target's own settings.
 */
@UtilityClass
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
class LoopTarget {

  /** Config key: the id of the processor to run. */
  private static final String PLUGIN_ID = "targetPluginId";

  /** Config key: the settings the target gets. */
  private static final String CONFIG = "targetConfig";

  /**
   * Looks up the target of a loop node.
   *
   * @param registryProvider the plugin registry
   * @param config the loop node configuration
   * @return the target, or an error if it is missing or is not a processor
   */
  /* default */ static Mono<ProcessorPlugin> resolve(
      final ObjectProvider<PluginRegistry> registryProvider, final Map<String, Object> config) {
    final String targetId = (String) config.get(PLUGIN_ID);
    final PluginRegistry registry = registryProvider.getIfAvailable();
    if (targetId == null || registry == null || !registry.contains(targetId)) {
      return Mono.error(new IllegalArgumentException("Target plugin not found: " + targetId));
    }
    final Plugin target = registry.get(targetId);
    if (target instanceof ProcessorPlugin processor) {
      return Mono.just(processor);
    }
    return Mono.error(
        new IllegalArgumentException("Target plugin must be a ProcessorPlugin: " + targetId));
  }

  /**
   * The settings the target gets.
   *
   * @param config the loop node configuration
   * @return the target's configuration, empty if none is set
   */
  @SuppressWarnings("unchecked")
  /* default */ static Map<String, Object> configOf(final Map<String, Object> config) {
    return (Map<String, Object>) config.getOrDefault(CONFIG, Map.of());
  }
}
