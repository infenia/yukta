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
package com.infenia.yukta.model;

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
