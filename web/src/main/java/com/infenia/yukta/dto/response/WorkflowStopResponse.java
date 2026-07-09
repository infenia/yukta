// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
