// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response from session creation with status and workflow information.
 *
 * @param sessionId the ID of the created session
 * @param createdWorkflows list of created workflow IDs
 * @param warnings list of warnings from session creation
 * @param success whether the session creation was successful
 */
@Schema(description = "Response from session creation")
public record SessionCreationResponse(
    @Schema(description = "The ID of the created session") String sessionId,
    @Schema(description = "List of created workflow IDs") List<String> createdWorkflows,
    @Schema(description = "List of warnings from session creation") List<String> warnings,
    @Schema(description = "Whether the session creation was successful") boolean success) {

  /** Compact constructor that wraps mutable lists with immutable views. */
  public SessionCreationResponse {
    createdWorkflows = List.copyOf(createdWorkflows);
    warnings = List.copyOf(warnings);
  }
}
