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
package com.infenia.yukta.plugin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

/** Base interface for all workflow plugins. */
public interface WorkflowPlugin {

  /**
   * Get the UI design metadata for this plugin.
   *
   * @return the UI design, or empty if using the default design
   */
  default Optional<UiDesign> getUiDesign() {
    return Optional.empty();
  }

  /**
   * Get the list of available output ports for this plugin.
   *
   * @return the list of output port names
   */
  default List<String> getOutputPorts() {
    return List.of("default");
  }

  /**
   * Get the list of available output ports for this plugin based on configuration.
   *
   * @param config the plugin configuration
   * @return the list of output port names
   */
  default List<String> getOutputPorts(Map<String, Object> config) {
    return getOutputPorts();
  }

  /**
   * Get the default timeout for this plugin.
   *
   * @return the default timeout
   */
  default Duration getDefaultTimeout() {
    return Duration.ofSeconds(30);
  }

  /**
   * Get the unique type string for this plugin.
   *
   * @return the plugin type
   */
  String getType();

  /**
   * Get the category of this plugin.
   *
   * @return the plugin category
   */
  PluginCategory getCategory();

  /**
   * Get a human-readable description of what this plugin does.
   *
   * @return the plugin description
   */
  default String getDescription() {
    return "";
  }

  /**
   * Get a human-readable description of the usage pattern for this plugin, including expected
   * configuration fields and input/output message structures.
   *
   * @return the plugin usage pattern
   */
  default String getUsagePattern() {
    return "";
  }

  /**
   * Validate the plugin configuration.
   *
   * @param config the configuration to validate
   * @return a Mono that completes if valid, or emits an error
   */
  default Mono<Void> validateConfig(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * One-time setup for the plugin instance.
   *
   * @param config the plugin configuration
   * @return a Mono that completes when initialization is done
   */
  default Mono<Void> initialize(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * Pre-execution preparation phase, typically for pre-parsing expressions.
   *
   * @param config the plugin configuration
   * @return a Mono that completes when preparation is done
   */
  default Mono<Void> prepare(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * Shutdown and cleanup resources used by the plugin.
   *
   * @param config the plugin configuration
   * @return a Mono that completes when cleanup is done
   */
  default Mono<Void> shutdown(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * Indicates if this plugin performs blocking I/O or long-running synchronous computation that
   * requires offloading to a dedicated thread pool (e.g., Virtual Threads).
   *
   * @return true if the plugin is blocking, false otherwise (default)
   */
  default boolean isBlocking() {
    return false;
  }
}
