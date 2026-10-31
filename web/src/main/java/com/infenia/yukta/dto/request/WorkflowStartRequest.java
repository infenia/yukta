// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.infenia.yukta.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Request object for starting a workflow.
 *
 * @param sessionId the session identifier
 * @param workflowId the workflow identifier
 * @param payload optional trigger payload, read by the workflow's trigger node (e.g. a chat
 *     message); an absent payload is treated as empty
 * @param initiator optional name of who or what starts the run (for example {@code yk check} or
 *     {@code schedule}); without one, the name of the API key that made the request is used
 */
@Schema(description = "Request object for starting a workflow")
public record WorkflowStartRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The unique workflow identifier", example = "quality-check") @NotBlank
        String workflowId,
    @Schema(description = "Optional trigger payload passed to the workflow's trigger node")
        Map<String, Object> payload,
    @Schema(
            description =
                "Optional name of who or what starts the run; shown as the run's initiator."
                    + " Defaults to the name of the API key.",
            example = "yk check",
            maxLength = 64)
        @Size(max = 64)
        String initiator) {

  /** Normalizes an absent payload to empty and defensively copies it. */
  public WorkflowStartRequest {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  /**
   * Creates a request that names no initiator.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payload the trigger payload
   */
  public WorkflowStartRequest(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    this(sessionId, workflowId, payload, null);
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
