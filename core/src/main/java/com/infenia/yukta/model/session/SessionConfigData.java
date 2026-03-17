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
package com.infenia.yukta.model.session;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.validation.ProjectPath;
import com.infenia.yukta.validation.SessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Data record for session configuration.
 *
 * @param sessionId the session identifier
 * @param description a human-readable description of the session
 * @param initiator the initiator name
 * @param tags additional tags for the session
 * @param projectPath the project path
 * @param workflows map of workflow definitions (DAGs)
 */
public record SessionConfigData(
    @SessionId String sessionId,
    @NotBlank(message = "Session description is mandatory")
        @Size(max = 256, message = "Session description must be at most 256 characters")
        String description,
    @NotBlank(message = "Initiator is mandatory") String initiator,
    Map<String, String> tags,
    @ProjectPath String projectPath,
    @NotEmpty(message = "Workflows cannot be empty") @Valid
        Map<String, WorkflowDefinition> workflows) {
  /** Compact constructor for immutability. */
  public SessionConfigData {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
    workflows = workflows != null ? Map.copyOf(workflows) : Map.of();
  }
}
