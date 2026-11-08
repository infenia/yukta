// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import com.infenia.yukta.plugin.control.SupervisorScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Finds where two supervisors would own the same unit. The units are a node, the run level of a
 * workflow and the server. A scope is read as: {@code Global} everything, {@code Session} every
 * workflow of the session, {@code Workflow} its run level and all its nodes, {@code Run} the run
 * level only, {@code Section} some nodes, {@code Server} the server.
 *
 * <p>A selector is matched against the workflow's real nodes when the shape is known. A selector
 * that takes no node yet (the node does not exist, or the workflow is unknown) is compared with the
 * other selectors as text: two prefixes overlap if one begins with the other, an id overlaps a
 * prefix it starts with, and a group only overlaps the same group. Nothing here changes behaviour:
 * it is called when a supervisor, an assignment or a workflow definition is saved.
 */
public final class OwnershipValidator {

  /** Looks up the shape of a workflow by session and workflow id; empty when it is not known. */
  private final BiFunction<@Nullable String, String, Optional<WorkflowShape>> shapes;

  /**
   * Creates the validator.
   *
   * @param shapes the shape of a workflow by session id (may be {@code null} for any) and id
   */
  public OwnershipValidator(
      final BiFunction<@Nullable String, String, Optional<WorkflowShape>> shapes) {
    this.shapes = shapes;
  }

  /** One supervisor's claim on a scope. */
  public record Claim(String supervisorId, SupervisorScope scope) {}

  /**
   * Overlaps of a new claim with the existing ones. Claims of the same supervisor never conflict.
   *
   * @param candidate the claim to add
   * @param existing the claims already held
   * @return one conflict per overlapping claim, empty when the claim is free
   */
  public List<OwnershipConflict> conflicts(final Claim candidate, final List<Claim> existing) {
    final List<OwnershipConflict> found = new ArrayList<>();
    for (final Claim other : existing) {
      if (!other.supervisorId().equals(candidate.supervisorId())) {
        overlap(candidate.scope(), other.scope())
            .map(
                disputed ->
                    new OwnershipConflict(candidate.supervisorId(), other.supervisorId(), disputed))
            .ifPresent(found::add);
      }
    }
    return found;
  }

  /**
   * Overlaps among the claims on one workflow, for the moment its definition is saved with a new
   * shape (a node was added, renamed or regrouped).
   *
   * @param claims every claim held
   * @return the overlaps between claims of different supervisors
   */
  public List<OwnershipConflict> conflictsAmong(final List<Claim> claims) {
    final List<OwnershipConflict> found = new ArrayList<>();
    for (int i = 0; i < claims.size(); i++) {
      found.addAll(conflicts(claims.get(i), claims.subList(i + 1, claims.size())));
    }
    return found;
  }

  private Optional<String> overlap(final SupervisorScope a, final SupervisorScope b) {
    if (a instanceof SupervisorScope.Global || b instanceof SupervisorScope.Global) {
      return Optional.of("everything is");
    }
    if (a instanceof SupervisorScope.Server || b instanceof SupervisorScope.Server) {
      return a instanceof SupervisorScope.Server && b instanceof SupervisorScope.Server
          ? Optional.of("the server is")
          : Optional.empty();
    }
    if (!compatible(a.sessionId(), b.sessionId())) {
      return Optional.empty();
    }
    if (a instanceof SupervisorScope.Session || b instanceof SupervisorScope.Session) {
      return Optional.of("the workflows of session " + describe(a, b) + " are");
    }
    if (!compatible(a.workflowId(), b.workflowId())) {
      return Optional.empty();
    }
    final String workflow = a.workflowId();
    if (a instanceof SupervisorScope.Workflow || b instanceof SupervisorScope.Workflow) {
      return Optional.of("workflow " + workflow + " is");
    }
    if (a instanceof SupervisorScope.Run && b instanceof SupervisorScope.Run) {
      return Optional.of("the run level of " + workflow + " is");
    }
    if (a instanceof SupervisorScope.Section x && b instanceof SupervisorScope.Section y) {
      return sections(workflow, x, y);
    }
    return Optional.empty();
  }

  private Optional<String> sections(
      final String workflow, final SupervisorScope.Section a, final SupervisorScope.Section b) {
    final Optional<WorkflowShape> shape = shapes.apply(a.sessionId(), workflow);
    final Set<String> nodes = new TreeSet<>();
    final List<String> text = new ArrayList<>();
    for (final String mine : a.nodes()) {
      for (final String theirs : b.nodes()) {
        final Set<String> bothTake = both(shape, mine, theirs);
        if (!bothTake.isEmpty()) {
          nodes.addAll(bothTake);
        } else if (unresolved(shape, mine) || unresolved(shape, theirs)) {
          if (selectorsOverlap(mine, theirs)) {
            text.add(mine.equals(theirs) ? mine : mine + " and " + theirs);
          }
        }
      }
    }
    if (nodes.isEmpty() && text.isEmpty()) {
      return Optional.empty();
    }
    final List<String> parts = new ArrayList<>(nodes.stream().map(n -> "node " + n).toList());
    parts.addAll(text.stream().map(t -> "selector " + t).toList());
    return Optional.of(String.join(", ", parts) + " of " + workflow + " are");
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

  /** A selector that takes no real node: it may take one later. */
  private static boolean unresolved(final Optional<WorkflowShape> shape, final String selector) {
    return shape.isEmpty() || shape.get().take(selector).isEmpty();
  }

  /** Text comparison of two selectors, for nodes that do not exist yet. */
  private static boolean selectorsOverlap(final String a, final String b) {
    final boolean groupA = a.startsWith(WorkflowShape.GROUP_PREFIX);
    final boolean groupB = b.startsWith(WorkflowShape.GROUP_PREFIX);
    if (groupA || groupB) {
      return a.equals(b);
    }
    final boolean prefixA = a.endsWith(".*");
    final boolean prefixB = b.endsWith(".*");
    final String headA = prefixA ? a.substring(0, a.length() - 1) : a;
    final String headB = prefixB ? b.substring(0, b.length() - 1) : b;
    if (prefixA && prefixB) {
      return headA.startsWith(headB) || headB.startsWith(headA);
    }
    if (prefixA) {
      return headB.startsWith(headA);
    }
    if (prefixB) {
      return headA.startsWith(headB);
    }
    return a.equals(b);
  }

  private static boolean compatible(final @Nullable String a, final @Nullable String b) {
    return a == null || b == null || a.equals(b);
  }

  private static String describe(final SupervisorScope a, final SupervisorScope b) {
    return a.sessionId() != null ? a.sessionId() : String.valueOf(b.sessionId());
  }
}
