// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What a supervisor owns.
 *
 * @param supervisorId the supervisor
 * @param assignments its assignments, whatever made them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorAssignmentList(
    String supervisorId, List<SupervisorAssignmentInfo> assignments) {

  /** Keeps an immutable copy of the assignments. */
  public SupervisorAssignmentList {
    assignments = List.copyOf(assignments);
  }
}
