// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.infenia.yukta.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Request object for session configuration.
 *
 * <p>Used to either initialize a new session or update an existing one with working directories,
 * workflow definitions, and metadata.
 *
 * @param sessionId the unique session identifier
 * @param name a human-readable name for the session
 * @param description a human-readable description of the session's purpose
 * @param initiator the user or system name that initiated the session
 * @param tags additional key-value metadata to categorize the session
 * @param projectPath the absolute path to the project root being managed
 * @param workflows map of workflow definition requests (DAGs) keyed by their unique workflow IDs
 */
@Schema(
    description =
        "Request object for session configuration, supporting both initialization and runtime"
            + " updates")
public record ConfigRequest(
    @Schema(
            description = "The unique session identifier used to track this context",
            example = "session-123")
        @SessionId
        String sessionId,
    @Schema(description = "A human-readable name for the session", example = "Production ETL v4")
        @NotBlank(message = "Session name is mandatory")
        @Size(max = 256, message = "Session name must be at most 256 characters")
        String name,
    @Schema(
            description =
                "A human-readable description of the session (optional; defaults to the name)",
            example = "Daily build and quality check")
        @Size(max = 256, message = "Session description must be at most 256 characters")
        String description,
    @Schema(
            description =
                "The name of the initiator (user or system); optional, defaults to anonymous",
            example = "AI Agent")
        String initiator,
    @Schema(
            description = "Metadata tags for categorization and filtering",
            example = "{\"environment\": \"prod\", \"client\": \"mobile-app\"}")
        Map<String, String> tags,
    @Schema(
            description =
                "The absolute root path of the project on the server's filesystem; optional,"
                    + " defaults to the current directory",
            example = "/home/user/workspace/project-alpha")
        @Size(max = 1024, message = "Working directory must be at most 1024 characters")
        String projectPath,
    @Schema(description = "A map of workflow definition requests keyed by workflow ID")
        @NotEmpty(message = "At least one workflow definition is required")
        @Valid
        Map<String, WorkflowDefinitionRequest> workflows) {

  /** Compact constructor to ensure immutability. */
  public ConfigRequest {
    name = blank(name) ? sessionId : name;
    description = blank(description) ? name : description;
    initiator = blank(initiator) ? "anonymous" : initiator;
    projectPath = blank(projectPath) ? "." : projectPath;
    tags = tags == null ? Map.of() : Map.copyOf(tags);
    workflows = workflows != null ? Map.copyOf(workflows) : Map.of();
  }

  private static boolean blank(final String value) {
    return value == null || value.isBlank();
  }

  /**
   * Whether every workflow is keyed by its own workflow ID; the session stores them by that ID.
   *
   * @return false if a map key differs from the workflow ID inside its definition
   */
  @JsonIgnore
  @Schema(hidden = true)
  @AssertTrue(message = "Each workflows key must equal the workflowId of its definition")
  public boolean isWorkflowsKeyedById() {
    return workflows.entrySet().stream()
        .allMatch(e -> e.getKey().equals(e.getValue().workflowId()));
  }
}
