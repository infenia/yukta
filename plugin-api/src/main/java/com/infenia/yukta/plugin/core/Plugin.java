// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

/** Base interface for all workflow plugins. */
public interface Plugin {

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
   * Get the default buffer size for this plugin.
   *
   * @return the default buffer size
   */
  default int getDefaultBufferSize() {
    return 1024;
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

  /**
   * Process an incoming administrative command from the Control Bus.
   *
   * @param signal the administrative command message
   * @return a Mono that emits the response to the command, or empty
   */
  default Mono<Message<?>> onControlSignal(Message<?> signal) {
    return Mono.empty();
  }

  /**
   * Validate this plugin's role in the workflow graph, given adjacent edges and configuration.
   * Called during workflow validation to enforce plugin-specific DAG constraints.
   *
   * @param context the workflow context containing this node's adjacent edges
   * @param config the plugin configuration
   * @return a Mono that completes if validation is successful, or emits an error
   */
  default Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * Indicates whether this plugin performs computationally heavy operations for the given
   * configuration. Used by validators to detect performance optimization opportunities.
   *
   * @param config the plugin configuration
   * @return true if the plugin is computationally heavy, false otherwise
   */
  default boolean isComputationallyHeavy(Map<String, Object> config) {
    return false;
  }

  /**
   * Indicates whether placement of this node at its current position is intentional and should not
   * trigger optimization suggestions. Used by FILTER and other nodes that may be intentionally
   * placed after heavy computation.
   *
   * @param config the plugin configuration
   * @return true if placement is intentional and optimization warnings should be suppressed
   */
  default boolean suppressOptimizationHint(Map<String, Object> config) {
    return false;
  }
}
