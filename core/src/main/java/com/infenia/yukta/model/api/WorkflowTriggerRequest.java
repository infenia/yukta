/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.model.api;

import com.infenia.yukta.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Request object for triggering a workflow.
 *
 * @param sessionId the session identifier
 * @param workflowId the workflow identifier
 * @param payload optional trigger payload
 */
@Schema(description = "Request object for triggering a workflow")
public record WorkflowTriggerRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The unique workflow identifier", example = "quality-check") @NotBlank
        String workflowId,
    @Schema(description = "Optional payload for the trigger") Map<String, Object> payload) {
  /** Compact constructor. */
  public WorkflowTriggerRequest {
    payload = payload != null ? Map.copyOf(payload) : Map.of();
  }
}
