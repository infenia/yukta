// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * A single execution known to the control bus, part of {@link ControlBusStatus}.
 *
 * @param sessionId the session identifier
 * @param executionId the execution identifier
 * @param status the execution status
 * @param duration the execution duration, or "running" if not yet finished
 */
public record ExecutionRecord(
    String sessionId, String executionId, String status, String duration) {}
