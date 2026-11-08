// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import com.infenia.yukta.plugin.control.Supervisor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules for who a supervisor reports to: each parent exists, none is the supervisor itself, no
 * cycle, and a chain is at most {@value #MAX_DEPTH} supervisors long. A parent needs no assignment
 * and no scope relation to its children.
 */
public final class SupervisorHierarchy {

  /** The longest chain of supervisors, a supervisor and its ancestors together. */
  public static final int MAX_DEPTH = 4;

  private SupervisorHierarchy() {}

  /**
   * Checks a supervisor against the stored ones, as the supervisor will be after the save.
   *
   * @param candidate the supervisor about to be saved
   * @param stored every stored supervisor by id (the candidate may be among them)
   * @throws IllegalArgumentException for a missing parent, a self reference, a cycle or a chain
   *     longer than {@value #MAX_DEPTH}
   */
  public static void validate(final Supervisor candidate, final Map<String, Supervisor> stored) {
    for (final String parent : candidate.parents()) {
      if (parent.equals(candidate.id())) {
        throw new IllegalArgumentException("A supervisor cannot report to itself");
      }
      if (!stored.containsKey(parent)) {
        throw new IllegalArgumentException("Parent supervisor not found: " + parent);
      }
    }
    final int depth = depth(candidate, stored, new HashSet<>());
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException(
          "Supervisors may report up at most " + MAX_DEPTH + " levels, this chain has " + depth);
    }
  }

  /**
   * Supervisors that name a supervisor as a parent.
   *
   * @param id the parent
   * @param stored every stored supervisor
   * @return the ids of its children, sorted
   */
  public static List<String> childrenOf(final String id, final Iterable<Supervisor> stored) {
    final List<String> children = new ArrayList<>();
    for (final Supervisor supervisor : stored) {
      if (supervisor.parents().contains(id)) {
        children.add(supervisor.id());
      }
    }
    children.sort(String::compareTo);
    return children;
  }

  /** The longest chain from a supervisor up; a path that meets itself is a cycle. */
  private static int depth(
      final Supervisor supervisor, final Map<String, Supervisor> stored, final Set<String> path) {
    if (!path.add(supervisor.id())) {
      throw new IllegalArgumentException(
          "Supervisors cannot report to each other in a cycle: " + supervisor.id());
    }
    int longest = 0;
    for (final String parent : supervisor.parents()) {
      final Supervisor next = stored.get(parent);
      if (next != null) {
        longest = Math.max(longest, depth(next, stored, path));
      }
    }
    path.remove(supervisor.id());
    return longest + 1;
  }
}
