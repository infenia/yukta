// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.time.Duration;
import java.util.Optional;

/** The eager check of the engine policy of each node: malformed, or not fit for its plugin. */
final class PolicyRules {

  private PolicyRules() {}

  /**
   * Finds the first node whose engine policy is malformed or not supported by its plugin category.
   *
   * <p>Checked eagerly, like the edge check, so the message names the node and the option.
   */
  /* default */ static Optional<String> findInvalidPolicy(
      final WorkflowDefinition def, final PluginRegistry registry) {
    for (final WorkflowDefinition.Node node : def.nodes()) {
      final NodePolicy policy;
      try {
        policy = NodePolicy.from(node.config());
      } catch (final IllegalArgumentException e) {
        return Optional.of(nodeProblem(node, e.getMessage()));
      }
      final Optional<String> backoff = findLongBackoff(node, policy, registry);
      if (backoff.isPresent()) {
        return backoff;
      }
      if (policy.concurrencyGroup() != null && !isProcessor(node, registry)) {
        return Optional.of(
            nodeProblem(node, "engine.concurrencyGroup is only supported on processor nodes"));
      }
      if (policy.perMessage()) {
        final var plugin = registry.get(node.type());
        if (plugin != null && plugin.getCategory() != PluginCategory.PROCESSOR) {
          return Optional.of(
              nodeProblem(
                  node,
                  "engine.retry, engine.onError and engine.elementTimeoutMs are only supported"
                      + " on processor nodes"));
        }
      }
    }
    return Optional.empty();
  }

  /** The message of a problem with one node, naming the node first. */
  private static String nodeProblem(final WorkflowDefinition.Node node, final String message) {
    return "Node '" + node.nodeId() + "': " + message;
  }

  /** False only for a node whose plugin is known and is not a processor. */
  private static boolean isProcessor(
      final WorkflowDefinition.Node node, final PluginRegistry registry) {
    final var plugin = registry.get(node.type());
    return plugin == null || plugin.getCategory() == PluginCategory.PROCESSOR;
  }

  /** The node's idle timeout, or null if it is unknown or not positive. */
  private static Duration nodeTimeout(
      final WorkflowDefinition.Node node, final PluginRegistry registry) {
    final Object configured = node.config().get("timeoutSeconds");
    final Plugin plugin = registry.get(node.type());
    final Duration timeout =
        configured instanceof Number seconds && seconds.longValue() > 0
            ? Duration.ofSeconds(seconds.longValue())
            : plugin == null ? null : plugin.getDefaultTimeout(node.config());
    return timeout != null && timeout.isPositive() ? timeout : null;
  }

  /**
   * A retry delay or element timeout as long as the node timeout makes the node time out first, so
   * the policy could never work as configured.
   */
  private static Optional<String> findLongBackoff(
      final WorkflowDefinition.Node node, final NodePolicy policy, final PluginRegistry registry) {
    final Duration element = policy.elementTimeout();
    final Duration deadline = policy.deadline();
    if (deadline != null && element != null && element.compareTo(deadline) >= 0) {
      return Optional.of(
          nodeProblem(
              node,
              "engine.elementTimeoutMs must be below engine.deadlineMs ("
                  + deadline.toMillis()
                  + " ms), which would end the node first"));
    }
    final Duration timeout = nodeTimeout(node, registry);
    if (timeout == null) {
      return Optional.empty();
    }
    final NodePolicy.Retry retry = policy.retry();
    if (retry != null && retry.maxBackoff().compareTo(timeout) >= 0) {
      return Optional.of(
          tooLong(
              node, "engine.retry.maxBackoffMs", timeout, "or the node times out while retrying"));
    }
    if (element != null && element.compareTo(timeout) >= 0) {
      return Optional.of(
          tooLong(node, "engine.elementTimeoutMs", timeout, "which would end the node first"));
    }
    return Optional.empty();
  }

  private static String tooLong(
      final WorkflowDefinition.Node node,
      final String option,
      final Duration timeout,
      final String consequence) {
    return nodeProblem(
        node,
        option + " must be below the node timeout (" + timeout.toSeconds() + " s), " + consequence);
  }
}
