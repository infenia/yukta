// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Details of a session including its workflows and metadata.
 *
 * @param sessionId the session identifier
 * @param name a human-readable name for the session
 * @param description a human-readable description of the session
 * @param initiator the user or system that initiated the session
 * @param tags flat list of tag keys associated with the session
 * @param projectPath the project path configured for the session
 * @param workflowIds the list of workflow identifiers defined in this session
 */
@Schema(description = "Details of a session including metadata and workflows")
public record SessionDetails(
    @Schema(description = "The session identifier", example = "sess-123") String sessionId,
    @Schema(description = "Human-readable name for the session", example = "Production ETL v4")
        String name,
    @Schema(description = "Description of the session", example = "Nightly batch processing")
        String description,
    @Schema(description = "The initiator name (user or system)", example = "system-scheduler")
        String initiator,
    @Schema(description = "Tags associated with the session", example = "[\"prod\", \"etl\"]")
        List<String> tags,
    @Schema(description = "Project path", example = "/data/pipelines/main") String projectPath,
    @Schema(description = "The list of workflow identifiers") List<String> workflowIds) {
  /** Compact constructor for defensive copying. */
  public SessionDetails {
    tags = tags == null ? List.of() : List.copyOf(tags);
    workflowIds = workflowIds != null ? List.copyOf(workflowIds) : List.of();
  }
}
