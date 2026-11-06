// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary of a Yukta session returned by the {@code list_sessions} MCP tool.
 *
 * @param sessionId the session identifier
 * @param workflowCount the number of workflows configured in the session
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionSummary(String sessionId, int workflowCount) {}
