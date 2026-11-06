// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.validation.ProjectPath;
import com.infenia.yukta.validation.SessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Data record for session configuration.
 *
 * @param sessionId the session identifier
 * @param name a human-readable name for the session
 * @param description a human-readable description of the session
 * @param initiator the initiator name
 * @param tags additional tags for the session
 * @param projectPath the working directory
 * @param workflows map of workflow definitions (DAGs)
 * @param supervisors the supervisors declared for the whole session, attached when it is saved
 */
public record SessionConfigData(
    @SessionId String sessionId,
    @NotBlank(message = "Session name is mandatory")
        @Size(max = 256, message = "Session name must be at most 256 characters")
        String name,
    @NotBlank(message = "Session description is mandatory")
        @Size(max = 256, message = "Session description must be at most 256 characters")
        String description,
    @NotBlank(message = "Initiator is mandatory") String initiator,
    Map<String, String> tags,
    @ProjectPath String projectPath,
    @NotEmpty(message = "Workflows cannot be empty") @Valid
        Map<String, WorkflowDefinition> workflows,
    @Size(max = 20, message = "A session declares at most 20 supervisors")
        List<@Valid @NotNull(message = "Supervisor cannot be null") SupervisorRef> supervisors) {
  /** Compact constructor for immutability. */
  public SessionConfigData {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
    workflows = workflows != null ? Map.copyOf(workflows) : Map.of();
    supervisors = supervisors == null ? List.of() : List.copyOf(supervisors);
  }

  /**
   * A session that declares no supervisors.
   *
   * @param sessionId the session identifier
   * @param name a human-readable name
   * @param description a human-readable description
   * @param initiator the initiator name
   * @param tags additional tags
   * @param projectPath the working directory
   * @param workflows map of workflow definitions
   */
  public SessionConfigData(
      final String sessionId,
      final String name,
      final String description,
      final String initiator,
      final Map<String, String> tags,
      final String projectPath,
      final Map<String, WorkflowDefinition> workflows) {
    this(sessionId, name, description, initiator, tags, projectPath, workflows, List.of());
  }
}
