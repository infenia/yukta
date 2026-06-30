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
import java.util.Collections;
import java.util.List;

/**
 * Response object for stopping a workflow.
 *
 * @param executionIds the list of stopped execution identifiers
 */
@Schema(description = "Response object for stopping workflow executions")
public record WorkflowStopResponse(
    @Schema(
            description = "List of stopped execution identifiers",
            example =
                "[\"550e8400-e29b-41d4-a716-446655440000\","
                    + " \"550e8400-e29b-41d4-a716-446655440001\"]")
        List<String> executionIds) {
  /**
   * Compact constructor to ensure immutability of the execution IDs list.
   *
   * @param executionIds the list of stopped execution identifiers
   */
  public WorkflowStopResponse(final List<String> executionIds) {
    this.executionIds = Collections.unmodifiableList(executionIds);
  }
}
