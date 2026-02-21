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
package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.ProjectPath;
import com.infenia.jagratha.validation.SessionId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Request object for configuration updates.
 *
 * @param sessionId the session identifier
 * @param initiator the initiator name
 * @param tags additional tags for the session
 * @param projectPath the project path
 * @param workflow the workflow definition (DAG)
 */
@Schema(description = "Request object for updating session configuration")
public record ConfigRequest(
    @Schema(description = "The unique session identifier", example = "session-123") @SessionId
        String sessionId,
    @Schema(description = "The initiator name", example = "John Doe")
        @NotBlank(message = "Initiator is mandatory")
        String initiator,
    @Schema(
            description = "Additional tags for the session",
            example = "{\"clientId\": \"client-1\"}")
        Map<String, String> tags,
    @Schema(description = "The root path of the project to manage", example = "/path/to/project")
        @ProjectPath
        String projectPath,
    @Schema(description = "The workflow definition (DAG) to execute")
        @NotNull(message = "Workflow definition is required")
        @Valid
        WorkflowDefinition workflow) {

  /** Compact constructor to ensure immutability. */
  public ConfigRequest {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
  }
}
