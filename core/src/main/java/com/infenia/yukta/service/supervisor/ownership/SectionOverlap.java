// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import com.infenia.yukta.plugin.control.SupervisorScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.experimental.UtilityClass;

/**
 * Whether two sections of one workflow own the same node, or selectors that may take one once it
 * exists. A node that both selectors take is named; the other pairs are compared as text.
 */
@UtilityClass
final class SectionOverlap {

  /**
   * The overlap of two sections of one workflow.
   *
   * @param shape the shape of the workflow, empty when it is not known
   * @param workflow the workflow id
   * @param a the first section
   * @param b the second section
   * @return what the sections share, empty when they share nothing
   */
  /* default */ static Optional<String> between(
      final Optional<WorkflowShape> shape,
      final String workflow,
      final SupervisorScope.Section a,
      final SupervisorScope.Section b) {
    final Set<String> nodes = new TreeSet<>();
    final List<String> text = new ArrayList<>();
    for (final String mine : a.nodes()) {
      for (final String theirs : b.nodes()) {
        collect(shape, mine, theirs, nodes, text);
      }
    }
    if (nodes.isEmpty() && text.isEmpty()) {
      return Optional.empty();
    }
    final List<String> parts = new ArrayList<>(nodes.stream().map(n -> "node " + n).toList());
    parts.addAll(text.stream().map(t -> "selector " + t).toList());
    return Optional.of(String.join(", ", parts) + " of " + workflow + " are");
  }

  private static void collect(
      final Optional<WorkflowShape> shape,
      final String mine,
      final String theirs,
      final Set<String> nodes,
      final List<String> text) {
    final Set<String> bothTake = both(shape, mine, theirs);
    nodes.addAll(bothTake);
    if (bothTake.isEmpty() && mayTakeLater(shape, mine, theirs)) {
      text.add(mine.equals(theirs) ? mine : mine + " and " + theirs);
    }
  }

  private static Set<String> both(
      final Optional<WorkflowShape> shape, final String mine, final String theirs) {
    if (shape.isEmpty()) {
      return Set.of();
    }
    final Set<String> common = new TreeSet<>(shape.get().take(mine));
    common.retainAll(shape.get().take(theirs));
    return common;
  }

  /** Whether one selector takes no real node yet, and the two selectors overlap as text. */
  private static boolean mayTakeLater(
      final Optional<WorkflowShape> shape, final String mine, final String theirs) {
    return (unresolved(shape, mine) || unresolved(shape, theirs)) && selectorsOverlap(mine, theirs);
  }

  /** A selector that takes no real node: it may take one later. */
  private static boolean unresolved(final Optional<WorkflowShape> shape, final String selector) {
    return shape.isEmpty() || shape.get().take(selector).isEmpty();
  }

  /** Text comparison of two selectors, for nodes that do not exist yet. */
  private static boolean selectorsOverlap(final String a, final String b) {
    if (a.startsWith(WorkflowShape.GROUP_PREFIX) || b.startsWith(WorkflowShape.GROUP_PREFIX)) {
      return a.equals(b);
    }
    return prefixOverlap(a, b);
  }

  private static boolean prefixOverlap(final String a, final String b) {
    final boolean prefixA = a.endsWith(".*");
    final boolean prefixB = b.endsWith(".*");
    if (prefixA && prefixB) {
      return head(a).startsWith(head(b)) || head(b).startsWith(head(a));
    }
    if (prefixA) {
      return b.startsWith(head(a));
    }
    if (prefixB) {
      return a.startsWith(head(b));
    }
    return a.equals(b);
  }

  /** The prefix of a selector that ends with {@code .*}: the selector without the wildcard. */
  private static String head(final String prefix) {
    return prefix.substring(0, prefix.length() - 1);
  }
}
