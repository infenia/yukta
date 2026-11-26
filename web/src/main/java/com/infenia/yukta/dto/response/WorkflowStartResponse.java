// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Response object for starting a workflow.
 *
 * @param executionId the unique execution identifier
 * @param reply the payload the run's REPLY terminal answered with, when the call waited for it
 */
@Schema(description = "Response object for triggering a workflow")
public record WorkflowStartResponse(
    @Schema(
            description = "The unique execution identifier",
            example = "550e8400-e29b-41d4-a716-446655440000")
        String executionId,
    @Schema(
            description =
                "What the run's REPLY terminal answered, when the call was made with ?wait= and"
                    + " the reply came in time (status 200); absent otherwise")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Nullable Object reply) {

  /**
   * A response with no reply.
   *
   * @param executionId the unique execution identifier
   */
  public WorkflowStartResponse(final String executionId) {
    this(executionId, null);
  }
}
