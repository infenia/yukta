// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The nodes of a workflow and the group each belongs to: what a selector is matched against. A
 * node's group is its declared {@code group} (the stage shown in the UI), or without one the part
 * of its id before the first dot, which is what the UI groups by.
 *
 * @param groupByNode node id to group; the group is {@code null} for an id without a dot and no
 *     declared group
 */
public record WorkflowShape(Map<String, @Nullable String> groupByNode) {

  /** Prefix of a selector that names a group. */
  public static final String GROUP_PREFIX = "group:";

  /** Keeps an unmodifiable copy. */
  public WorkflowShape {
    groupByNode = Collections.unmodifiableMap(new LinkedHashMap<>(groupByNode));
  }

  /**
   * The shape of a stored definition.
   *
   * @param definition the definition
   * @return its nodes and groups
   */
  public static WorkflowShape from(final WorkflowDefinition definition) {
    return new WorkflowShape(
        definition.nodes().stream()
            .<Map<String, @Nullable String>>collect(
                LinkedHashMap::new,
                (groups, node) -> groups.put(node.nodeId(), groupOf(node)),
                Map::putAll));
  }

  /** The declared group of a node, or without one the part of its id before the first dot. */
  private static @Nullable String groupOf(final WorkflowDefinition.Node node) {
    final String id = node.nodeId();
    final int dot = id.indexOf('.');
    return node.group() != null ? node.group() : dot > 0 ? id.substring(0, dot) : null;
  }

  /**
   * The nodes a selector takes: an id, {@code <prefix>.*} or {@code group:<name>}.
   *
   * @param selector the selector
   * @return the node ids, empty when it takes none yet
   */
  public Set<String> take(final String selector) {
    final Set<String> taken = new TreeSet<>();
    for (final Map.Entry<String, @Nullable String> node : groupByNode.entrySet()) {
      final boolean takes;
      if (selector.startsWith(GROUP_PREFIX)) {
        takes = selector.substring(GROUP_PREFIX.length()).equals(node.getValue());
      } else if (selector.endsWith(".*")) {
        takes = node.getKey().startsWith(selector.substring(0, selector.length() - 1));
      } else {
        takes = node.getKey().equals(selector);
      }
      if (takes) {
        taken.add(node.getKey());
      }
    }
    return taken;
  }
}
