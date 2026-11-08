// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Response record containing complete session configuration data.
 *
 * @param sessionId the session identifier
 * @param name a human-readable name for the session
 * @param description a human-readable description of the session
 * @param initiator the initiator name
 * @param tags additional tags for the session
 * @param projectPath the working directory
 * @param workflows map of workflow definitions (DAGs)
 * @param supervisors the supervisors declared for the whole session
 */
public record SessionConfigResponse(
    String sessionId,
    String name,
    String description,
    String initiator,
    Map<String, String> tags,
    String projectPath,
    Map<String, WorkflowDefinition> workflows,
    @JsonProperty("assignments") List<SupervisorRef> supervisors) {
  /** Compact constructor for defensive copying and immutability. */
  public SessionConfigResponse {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
    workflows = workflows != null ? Map.copyOf(workflows) : Map.of();
    supervisors = supervisors == null ? List.of() : List.copyOf(supervisors);
  }

  /**
   * A response without declared supervisors.
   *
   * @param sessionId the session identifier
   * @param name the name
   * @param description the description
   * @param initiator the initiator
   * @param tags the tags
   * @param projectPath the working directory
   * @param workflows the workflow definitions
   */
  public SessionConfigResponse(
      final String sessionId,
      final String name,
      final String description,
      final String initiator,
      final Map<String, String> tags,
      final String projectPath,
      final Map<String, WorkflowDefinition> workflows) {
    this(sessionId, name, description, initiator, tags, projectPath, workflows, List.of());
  }

  @Override
  public Map<String, String> tags() {
    return Collections.unmodifiableMap(tags);
  }

  @Override
  public Map<String, WorkflowDefinition> workflows() {
    return Collections.unmodifiableMap(workflows);
  }
}
