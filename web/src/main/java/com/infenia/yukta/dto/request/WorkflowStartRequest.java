// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.infenia.yukta.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Request object for starting a workflow.
 *
 * @param sessionId the session identifier
 * @param workflowId the workflow identifier
 * @param payload optional trigger payload, read by the workflow's trigger node (e.g. a chat
 *     message); an absent payload is treated as empty
 */
@Schema(description = "Request object for starting a workflow")
public record WorkflowStartRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The unique workflow identifier", example = "quality-check") @NotBlank
        String workflowId,
    @Schema(description = "Optional trigger payload passed to the workflow's trigger node")
        Map<String, Object> payload) {

  /** Normalizes an absent payload to empty and defensively copies it. */
  public WorkflowStartRequest {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  /**
   * Creates a request without a trigger payload.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   */
  public WorkflowStartRequest(final String sessionId, final String workflowId) {
    this(sessionId, workflowId, Map.of());
  }
}
