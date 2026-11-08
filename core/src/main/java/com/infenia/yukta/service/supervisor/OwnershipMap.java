// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Who owns each part of a workflow: its run level and each node. */
@Component
public class OwnershipMap {

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

  private final ControlReviewer reviewer;
  private final WorkflowDefinitionStore definitions;

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
  public Mono<Ownership> of(final String sessionId, final String workflowId) {
    return definitions
        .find(sessionId, workflowId)
        .switchIfEmpty(
            Mono.error(
                new NotFoundException("Workflow not found: " + sessionId + "/" + workflowId)))
        .map(
            definition -> {
              final Map<String, String> nodes = new LinkedHashMap<>();
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
                          sessionId,
                          workflowId,
                          null,
                          new ExecutionControlCommand.PauseWorkflowCommand("e"))
                      .via(),
                  nodes);
            });
  }
}
