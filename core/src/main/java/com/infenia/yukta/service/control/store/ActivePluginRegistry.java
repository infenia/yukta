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
package com.infenia.yukta.service.control.store;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Registry of plugin instances actively servicing a workflow node, keyed by {@code workflowId +
 * "\0" + nodeId}.
 *
 * <p>Node-assembler strategies register a node's plugin when its stream/completion is subscribed
 * to, and unregister it when that stream terminates (success, error, or cancellation). Because
 * plugins are shared singletons (one instance per type), the same composite key can be registered
 * by more than one concurrently running execution of the same workflow and node; registrations are
 * therefore refcounted so that one execution finishing does not remove a plugin still needed by
 * another. The map entry is removed, and {@link ControlSignalHandler#removeNode(String)} is
 * invoked, only when the last outstanding registration for a key is released.
 *
 * <p>Extracted as a standalone bean (rather than living on {@code ControlBusService}) so that the
 * orchestrator's node-assembler strategies can depend on it directly without a circular dependency
 * back through {@code ControlBusService}/{@code WorkflowOrchestrator}.
 */
@Slf4j
@Component
@Validated
@RequiredArgsConstructor
public class ActivePluginRegistry {

  /** Separator used to create composite keys from workflow and node IDs. */
  private static final String COMPOSITE_KEY_SEPARATOR = "\0";

  /** List of signal handlers notified when a node's registration is fully released. */
  private final List<ControlSignalHandler> handlers;

  /** Map of active plugin registrations by composite key. */
  private final Map<String, Registration> activePlugins = new ConcurrentHashMap<>();

  /** A registered plugin instance with a count of outstanding registrations for its key. */
  private record Registration(Plugin plugin, AtomicInteger refCount) {}

  /**
   * Create a composite key from workflow ID and node ID.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return the composite key
   */
  private static String compositeKey(final String workflowId, final String nodeId) {
    return workflowId + COMPOSITE_KEY_SEPARATOR + nodeId;
  }

  /**
   * Register a plugin instance as actively servicing a workflow node.
   *
   * <p>If the composite key is already registered (e.g. a concurrent execution of the same workflow
   * and node), increments the outstanding-registration count instead of replacing the entry.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  public void register(
      @NotBlank final String workflowId, @NotBlank final String nodeId, final Plugin plugin) {
    final String key = compositeKey(workflowId, nodeId);
    activePlugins.compute(
        key,
        (k, existing) -> {
          if (existing == null) {
            return new Registration(plugin, new AtomicInteger(1));
          }
          existing.refCount().incrementAndGet();
          return existing;
        });
    log.atDebug()
        .addArgument(nodeId)
        .addArgument(workflowId)
        .addArgument(plugin.getClass().getSimpleName())
        .log("Registered plugin {} for node: {} in workflow: {}");
  }

  /**
   * Look up the plugin instance currently registered for a workflow node.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return an Optional containing the registered plugin, or empty if none is registered
   */
  public Optional<Plugin> lookup(@NotBlank final String workflowId, @NotBlank final String nodeId) {
    return Optional.ofNullable(activePlugins.get(compositeKey(workflowId, nodeId)))
        .map(Registration::plugin);
  }

  /**
   * Release one outstanding registration for a workflow node.
   *
   * <p>Only removes the entry and notifies {@link ControlSignalHandler#removeNode(String)} when
   * this releases the last outstanding registration for the key. Releasing a key with no
   * outstanding registrations is a no-op (no handlers are notified).
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   */
  public void unregister(@NotBlank final String workflowId, @NotBlank final String nodeId) {
    final String key = compositeKey(workflowId, nodeId);
    final boolean[] fullyReleased = {false};
    activePlugins.computeIfPresent(
        key,
        (k, registration) -> {
          if (registration.refCount().decrementAndGet() <= 0) {
            fullyReleased[0] = true;
            return null;
          }
          return registration;
        });
    if (fullyReleased[0]) {
      log.atDebug()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Unregistered plugin for node: {} in workflow: {}");
      handlers.forEach(h -> h.removeNode(key));
    } else {
      log.atTrace()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Released registration for node: {} in workflow: {} (still in use elsewhere)");
    }
  }
}
