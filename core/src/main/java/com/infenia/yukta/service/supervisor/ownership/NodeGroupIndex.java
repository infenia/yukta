// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.SupervisorScope;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The groups of the stored workflows, per session, so that a {@code group:<name>} selector can be
 * matched at run time. A node's group is its {@code group}, or the part of its id before the first
 * dot ({@link WorkflowShape}). Filled from the definitions whenever the supervisors of a session
 * are reconciled (every session save and rollback, and at start-up); until then a group matches no
 * node, so it owns nothing rather than too much.
 */
@Component
public class NodeGroupIndex {

  private final Map<String, Map<String, WorkflowShape>> bySession = new ConcurrentHashMap<>();

  /**
   * Keeps the shapes of a session's workflows.
   *
   * @param sessionId the session
   * @param workflows its stored workflows by id
   * @return true if a group of the session changed
   */
  public boolean put(final String sessionId, final Map<String, WorkflowDefinition> workflows) {
    final Map<String, WorkflowShape> shapes =
        workflows.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> WorkflowShape.from(entry.getValue())));
    return !shapes.equals(bySession.put(sessionId, shapes));
  }

  /**
   * Forgets a session that was deleted.
   *
   * @param sessionId the session
   */
  public void forget(final String sessionId) {
    bySession.remove(sessionId);
  }

  /**
   * A scope with its {@code group:} selectors resolved to the nodes they name now. Only a section
   * that names its session can be resolved; any other scope is returned as it is.
   *
   * @param scope the scope as written
   * @return the scope to match with
   */
  public SupervisorScope resolve(final SupervisorScope scope) {
    if (!(scope instanceof SupervisorScope.Section section)
        || !section.hasGroups()
        || section.sessionId() == null) {
      return scope;
    }
    final WorkflowShape shape =
        bySession.getOrDefault(section.sessionId(), Map.of()).get(section.workflowId());
    final Set<String> members = new TreeSet<>();
    if (shape != null) {
      section.nodes().stream()
          .filter(selector -> selector.startsWith(WorkflowShape.GROUP_PREFIX))
          .forEach(selector -> members.addAll(shape.take(selector)));
    }
    return section.withGroupMembers(members);
  }
}
