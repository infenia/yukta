// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.store;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.ControlKey;
import com.infenia.yukta.service.control.directive.ControlSignalHandler;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Registry of plugin instances actively servicing a node of a run, keyed by {@link ControlKey}
 * (workflow, execution and node).
 *
 * <p>Node-assembler strategies register a node's plugin when its stream/completion is subscribed
 * to, and unregister it when that stream terminates (success, error, or cancellation). Two runs of
 * the same workflow have their own registrations, so one run finishing never removes the plugin of
 * another, and its handlers' state ({@link ControlSignalHandler#removeNode(String)}) is cleared for
 * that run only. Within one run a key can still be registered more than once, so registrations are
 * refcounted: the map entry is removed, and the handlers notified, only when the last outstanding
 * registration for a key is released.
 *
 * <p>Registering without an execution id is for a caller that has no run; looking up without one
 * finds the node of any run.
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

  /** List of signal handlers notified when a node's registration is fully released. */
  private final List<ControlSignalHandler> handlers;

  /** Map of active plugin registrations by {@link ControlKey}. */
  private final Map<String, Registration> activePlugins = new ConcurrentHashMap<>();

  /** A registered plugin instance with a count of outstanding registrations for its key. */
  private record Registration(Plugin plugin, AtomicInteger refCount) {}

  /**
   * Register a plugin instance as servicing a workflow node, outside any run.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  public void register(
      @NotBlank final String workflowId, @NotBlank final String nodeId, final Plugin plugin) {
    register(workflowId, null, nodeId, plugin);
  }

  /**
   * Register a plugin instance as servicing a node of a run.
   *
   * <p>If the key is already registered (the same node registered again within the run), increments
   * the outstanding-registration count instead of replacing the entry.
   *
   * @param workflowId the workflow identifier
   * @param executionId the run, or null for none
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  public void register(
      @NotBlank final String workflowId,
      final @Nullable String executionId,
      @NotBlank final String nodeId,
      final Plugin plugin) {
    activePlugins.compute(
        ControlKey.forNode(workflowId, executionId, nodeId),
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
        .addArgument(executionId)
        .addArgument(plugin.getClass().getSimpleName())
        .log("Registered plugin {} for node: {} in workflow: {}, run: {}");
  }

  /**
   * Look up the plugin instance registered for a workflow node in any run.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   * @return an Optional containing the registered plugin, or empty if none is registered
   */
  public Optional<Plugin> lookup(@NotBlank final String workflowId, @NotBlank final String nodeId) {
    return Optional.ofNullable(activePlugins.get(ControlKey.forNode(workflowId, null, nodeId)))
        .or(
            () ->
                activePlugins.entrySet().stream()
                    .filter(entry -> ControlKey.isFor(entry.getKey(), workflowId, nodeId))
                    .map(Map.Entry::getValue)
                    .findFirst())
        .map(Registration::plugin);
  }

  /**
   * Look up the plugin instance registered for a node of a run.
   *
   * @param workflowId the workflow identifier
   * @param executionId the run
   * @param nodeId the node identifier
   * @return an Optional containing the registered plugin, or empty if none is registered
   */
  public Optional<Plugin> lookup(
      @NotBlank final String workflowId,
      final @Nullable String executionId,
      @NotBlank final String nodeId) {
    return Optional.ofNullable(
            activePlugins.get(ControlKey.forNode(workflowId, executionId, nodeId)))
        .map(Registration::plugin);
  }

  /**
   * Release one outstanding registration made outside any run.
   *
   * @param workflowId the workflow identifier
   * @param nodeId the node identifier
   */
  public void unregister(@NotBlank final String workflowId, @NotBlank final String nodeId) {
    unregister(workflowId, null, nodeId);
  }

  /**
   * Release one outstanding registration for a node of a run.
   *
   * <p>Only removes the entry and notifies {@link ControlSignalHandler#removeNode(String)} when
   * this releases the last outstanding registration for the key. Releasing a key with no
   * outstanding registrations is a no-op (no handlers are notified).
   *
   * @param workflowId the workflow identifier
   * @param executionId the run, or null for none
   * @param nodeId the node identifier
   */
  public void unregister(
      @NotBlank final String workflowId,
      final @Nullable String executionId,
      @NotBlank final String nodeId) {
    final String key = ControlKey.forNode(workflowId, executionId, nodeId);
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
          .addArgument(executionId)
          .log("Unregistered plugin for node: {} in workflow: {}, run: {}");
      handlers.forEach(h -> h.removeNode(key));
    } else {
      log.atTrace()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .addArgument(executionId)
          .log("Released registration for node: {} in workflow: {}, run: {} (still in use)");
    }
  }
}
