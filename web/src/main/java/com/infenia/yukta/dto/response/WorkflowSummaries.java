// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response wrapper containing a list of workflow summaries for a session.
 *
 * @param workflows the list of workflow summaries
 */
@Schema(description = "List of workflow summaries for a session")
public record WorkflowSummaries(
    @Schema(description = "Array of workflow summaries") List<WorkflowSummary> workflows) {
  /** Compact constructor for defensive copying. */
  public WorkflowSummaries {
    workflows = workflows == null ? List.of() : List.copyOf(workflows);
  }
}
