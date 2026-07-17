// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Execution information for a session, part of {@link ControlBusStatus}.
 *
 * @param sessionId the session identifier
 * @param activeExecutions the number of executions not yet in a terminal state
 * @param totalWorkflows the total number of workflows configured in the session
 */
public record SessionExecutionInfo(String sessionId, int activeExecutions, int totalWorkflows) {}
