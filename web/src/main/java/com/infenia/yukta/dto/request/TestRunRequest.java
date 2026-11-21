// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * A test run of a draft workflow that is not saved.
 *
 * @param workflow the draft
 * @param untilNodeId run only this node and the nodes before it; absent runs the whole draft
 * @param stepOnly run only {@code untilNodeId}, fed the payload as if its parent had sent it
 * @param payload the start payload the trigger emits; absent is empty
 */
@Schema(description = "A test run of a draft workflow that is not saved")
public record TestRunRequest(
    @Schema(description = "The draft workflow") @NotNull @Valid WorkflowDefinitionRequest workflow,
    @Schema(
            description = "Run only this node and every node before it; absent runs the draft",
            nullable = true)
        String untilNodeId,
    @Schema(description = "Run only untilNodeId, fed the payload (needs untilNodeId)")
        Boolean stepOnly,
    @Schema(description = "The start payload the trigger emits; absent is empty", nullable = true)
        Map<String, Object> payload) {

  /** Normalizes an absent payload to empty and an absent {@code stepOnly} to false. */
  public TestRunRequest {
    stepOnly = Boolean.TRUE.equals(stepOnly);
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }
}
