// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.review.ControlReviewer;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Who owns each part of a workflow: its run level and each node. */
@Component
public class OwnershipMap {

  private final ControlReviewer reviewer;
  private final WorkflowDefinitionStore definitions;

  /**
   * The owners of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param defaultSupervisor whether a unit nobody owns is carried by the default supervisor
   * @param run who carries the run level: a supervisor id or {@code default}
   * @param nodes who carries each node, by node id
   */
  public record Ownership(
      String sessionId,
      String workflowId,
      boolean defaultSupervisor,
      String run,
      Map<String, String> nodes) {

    /** Keeps an ordered, unmodifiable copy of the owners. */
    public Ownership {
      nodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
  }

  /**
   * Creates the map.
   *
   * @param reviewer finds the owner of a unit
   * @param definitions holds the workflow definitions
   */
  public OwnershipMap(final ControlReviewer reviewer, final WorkflowDefinitionStore definitions) {
    this.reviewer = reviewer;
    this.definitions = definitions;
  }

  /**
   * Who owns the run level and each node of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the owners; errors with {@link NotFoundException} for an unknown workflow
   */
  public Mono<Ownership> ownersOf(final String sessionId, final String workflowId) {
    return definitions
        .find(sessionId, workflowId)
        .switchIfEmpty(
            Mono.error(
                new NotFoundException("Workflow not found: " + sessionId + "/" + workflowId)))
        .map(definition -> owners(sessionId, workflowId, definition));
  }

  /**
   * A workflow nobody owns: its run level and every node are carried by the default supervisor.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   */
  public record WorkflowRef(String sessionId, String workflowId) {}

  /**
   * The workflows of the given sessions that nobody owns, session by session.
   *
   * @param sessionIds the sessions to look at
   * @return the workflows whose run level and nodes all fall to the default supervisor
   */
  public Flux<WorkflowRef> unowned(final Flux<String> sessionIds) {
    return sessionIds.concatMap(
        sessionId ->
            definitions
                .findAll(sessionId)
                .flatMapIterable(Map::entrySet)
                .filter(
                    entry -> {
                      final Ownership owned = owners(sessionId, entry.getKey(), entry.getValue());
                      return ControlReviewer.DEFAULT_VIA.equals(owned.run())
                          && owned.nodes().values().stream()
                              .allMatch(ControlReviewer.DEFAULT_VIA::equals);
                    })
                .map(entry -> new WorkflowRef(sessionId, entry.getKey())));
  }

  private Ownership owners(
      final String sessionId, final String workflowId, final WorkflowDefinition definition) {
    final Map<String, String> nodes = new LinkedHashMap<>(); // NOPMD - local, ordered
    definition
        .nodes()
        .forEach(
            node ->
                nodes.put(
                    node.nodeId(),
                    reviewer
                        .capability(
                            sessionId,
                            workflowId,
                            node.nodeId(),
                            new ExecutionControlCommand.PauseNodeCommand("e", "n"))
                        .via()));
    return new Ownership(
        sessionId,
        workflowId,
        reviewer.defaultSupervisorEnabled(),
        reviewer
            .capability(
                sessionId, workflowId, null, new ExecutionControlCommand.PauseWorkflowCommand("e"))
            .via(),
        nodes);
  }
}
