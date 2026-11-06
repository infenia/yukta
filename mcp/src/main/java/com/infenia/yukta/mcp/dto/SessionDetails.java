// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Details of a Yukta session returned by the {@code get_session_details} MCP tool.
 *
 * @param sessionId the session identifier
 * @param workflowIds the workflow identifiers configured in the session
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionDetails(String sessionId, List<String> workflowIds) {

  /** Compact constructor to ensure immutability. */
  public SessionDetails {
    workflowIds = workflowIds == null ? List.of() : List.copyOf(workflowIds);
  }
}
