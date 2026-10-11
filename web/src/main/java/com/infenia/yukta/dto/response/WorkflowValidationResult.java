// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a dry-run validation of a draft workflow.
 *
 * @param valid whether the workflow would be accepted for execution
 * @param message the first problem found, or {@code null} when valid
 * @param outputPorts the output ports of each node for its configuration, keyed by node id
 */
@Schema(description = "Result of validating a draft workflow without saving it")
public record WorkflowValidationResult(
    @Schema(description = "True when the workflow would be accepted") boolean valid,
    @Schema(description = "The first problem found; null when valid") String message,
    @Schema(description = "Output ports per node id, for the node's configuration")
        Map<String, List<String>> outputPorts) {

  /** Compact constructor to ensure immutability. */
  public WorkflowValidationResult {
    outputPorts = outputPorts != null ? Map.copyOf(outputPorts) : Map.of();
  }
}
