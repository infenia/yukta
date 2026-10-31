// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO representing a single session in a list view.
 *
 * <p>Contains a lean summary of session data suitable for display in tables, without the full
 * workflow bodies.
 *
 * @param sessionId the unique session identifier
 * @param name a human-readable name for the session
 * @param description a human-readable description of the session
 * @param initiator the user or system that initiated the session
 * @param tags flat list of tag keys associated with the session
 * @param projectPath the working directory configured for the session
 * @param workflowCount the number of workflows defined in this session
 * @param lastRun the session's newest run, or null when it has none
 */
@Schema(description = "A session summary for list/table display")
@With
public record SessionListItem(
    @Schema(description = "The unique session identifier", example = "sess-123") String sessionId,
    @Schema(description = "Human-readable name for the session", example = "Production ETL v4")
        String name,
    @Schema(description = "Description of the session", example = "Nightly batch processing")
        String description,
    @Schema(description = "The initiator name (user or system)", example = "system-scheduler")
        String initiator,
    @Schema(description = "Tags associated with the session", example = "[\"prod\", \"etl\"]")
        List<String> tags,
    @Schema(description = "Working directory", example = "/data/pipelines/main") String projectPath,
    @Schema(description = "Number of workflows", example = "24") int workflowCount,
    @Schema(description = "The session's newest run, null when it has none", nullable = true)
        @Nullable WorkflowExecutionSummary lastRun) {

  /** Compact constructor for defensive copying. */
  public SessionListItem {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
