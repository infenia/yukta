// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Details of a session including its workflows.
 *
 * @param sessionId the session identifier
 * @param workflowIds the list of workflow identifiers defined in this session
 */
@Schema(description = "Details of a session")
public record SessionDetails(
    @Schema(description = "The session identifier") String sessionId,
    @Schema(description = "The list of workflow identifiers") List<String> workflowIds) {
  /** Compact constructor. */
  public SessionDetails {
    workflowIds = workflowIds != null ? List.copyOf(workflowIds) : List.of();
  }
}
