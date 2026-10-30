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
 * @param warnings what would run but probably not as meant, such as a port nothing reads
 */
@Schema(description = "Result of validating a draft workflow without saving it")
public record WorkflowValidationResult(
    @Schema(description = "True when the workflow would be accepted") boolean valid,
    @Schema(description = "The first problem found; null when valid") String message,
    @Schema(description = "Output ports per node id, for the node's configuration")
        Map<String, List<String>> outputPorts,
    @Schema(description = "Problems that do not stop the workflow, for the editor to show")
        List<Warning> warnings) {

  /**
   * Something in an accepted workflow that probably does not do what was meant.
   *
   * @param nodeId the node it is about
   * @param field the config field or output port it is about
   * @param message what is wrong, for a person
   */
  @Schema(description = "A problem that does not stop the workflow")
  public record Warning(
      @Schema(description = "The node it is about") String nodeId,
      @Schema(description = "The config field or output port it is about") String field,
      @Schema(description = "What is wrong") String message) {}

  /** Compact constructor to ensure immutability. */
  public WorkflowValidationResult {
    outputPorts = outputPorts != null ? Map.copyOf(outputPorts) : Map.of();
    warnings = warnings != null ? List.copyOf(warnings) : List.of();
  }
}
