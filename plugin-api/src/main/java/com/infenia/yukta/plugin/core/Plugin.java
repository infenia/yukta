// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Base interface for all workflow plugins. */
public interface Plugin {

  /**
   * Get the human-facing metadata of this plugin: description, usage pattern and UI design.
   *
   * @return the descriptor; {@link PluginDescriptor#EMPTY} by default
   */
  default PluginDescriptor describe() {
    return PluginDescriptor.EMPTY;
  }

  /**
   * Get the configuration parameters of this plugin, so a UI can render a form.
   *
   * @return the declared fields; empty by default, in which case a UI falls back to a raw JSON
   *     editor and the {@link #describe() usage pattern}
   */
  default List<ConfigField> getConfigSchema() {
    return List.of();
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
   * Get the default timeout for a node with the given configuration. Plugins whose run time depends
   * on their configuration (a process timeout, an LLM call with retries) override this, so a long
   * step is not cut off by the generic default.
   *
   * @param config the node configuration
   * @return the default timeout
   */
  default Duration getDefaultTimeout(final Map<String, Object> config) {
    return getDefaultTimeout();
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
   * <p>Plugin instances are shared singletons: one instance serves every node of its type, in every
   * workflow and session. Treat the instance as stateless and keep per-node state out of it, in the
   * object returned by {@link #forNode}; the node configuration is passed to every call. This
   * method may run many times with different configurations, so it must be idempotent and limited
   * to warming caches.
   *
   * @param config the plugin configuration
   * @return a Mono that completes when initialization is done
   */
  default Mono<Void> initialize(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * Pre-execution preparation phase, typically for pre-parsing expressions. The workflow preparator
   * calls it for every node right after {@link #initialize}, so the same rules apply.
   *
   * @param config the plugin configuration
   * @return a Mono that completes when preparation is done
   */
  default Mono<Void> prepare(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * Get the object that serves one node of this type. The engine calls it once per node when a run
   * is assembled and uses the result for every call of that node, so per-node state (a window, a
   * cache, a parsed config) belongs in the returned object's fields, never in a shared instance.
   *
   * <p>The default returns {@code this}, which is right for a plugin that keeps no state. A plugin
   * with state returns a new instance of its own kind (the engine casts it to the interface it
   * runs). A {@code null} result also means {@code this}.
   *
   * @param config the node configuration
   * @return the plugin to use for this node
   */
  default Plugin forNode(final Map<String, Object> config) {
    return this;
  }

  /**
   * Shutdown and cleanup resources used by the plugin.
   *
   * <p>The workflow engine does not call this, because plugin instances are shared. Release
   * long-lived resources with the owning container's lifecycle (for example {@code @PreDestroy}).
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
