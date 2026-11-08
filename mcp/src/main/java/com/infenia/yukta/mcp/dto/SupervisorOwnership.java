// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Who owns each part of a workflow.
 *
 * @param sessionId the session
 * @param workflowId the workflow
 * @param defaultSupervisor whether a unit that nobody owns is carried by the default supervisor
 * @param run who carries the run level: a supervisor id or {@code default}
 * @param nodes who carries each node, by node id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorOwnership(
    String sessionId,
    String workflowId,
    boolean defaultSupervisor,
    String run,
    Map<String, String> nodes) {

  /** Keeps an unmodifiable copy of the owners. */
  public SupervisorOwnership {
    nodes = Map.copyOf(nodes);
  }
}
