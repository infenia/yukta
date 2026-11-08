// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a supervisor would have done on a past run, in dry run.
 *
 * @param supervisorId the supervisor
 * @param executionId the past run
 * @param decisions what it would have sent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorReplay(String supervisorId, String executionId, List<Decision> decisions) {

  /**
   * One command it would have sent.
   *
   * @param command the command type
   * @param executionId the run it addresses
   * @param nodeId the node, or {@code null}
   * @param cause the cause
   * @param reason the reason, or {@code null}
   * @param allowed whether its capabilities would let it through
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Decision(
      String command,
      String executionId,
      @Nullable String nodeId,
      String cause,
      @Nullable String reason,
      boolean allowed) {}
}
