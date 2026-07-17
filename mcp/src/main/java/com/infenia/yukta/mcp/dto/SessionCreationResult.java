// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * Result of creating a session via the {@code create_session} MCP tool.
 *
 * @param sessionId the created session identifier, empty if creation failed
 * @param createdWorkflows the workflow identifiers created with the session
 * @param warnings warnings or errors raised during creation
 * @param success whether the session was created successfully
 */
public record SessionCreationResult(
    String sessionId, List<String> createdWorkflows, List<String> warnings, boolean success) {

  /** Compact constructor to ensure immutability. */
  public SessionCreationResult {
    createdWorkflows = createdWorkflows == null ? List.of() : List.copyOf(createdWorkflows);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
