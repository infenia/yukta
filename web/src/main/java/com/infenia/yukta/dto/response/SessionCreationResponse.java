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
