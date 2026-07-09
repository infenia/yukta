// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.time.LocalDateTime;

/**
 * Metadata summary for a plugin execution.
 *
 * @param executionId unique execution identifier
 * @param sessionId session identifier
 * @param startTime when execution started
 * @param endTime when execution ended (null if still running)
 * @param entryCount number of log entries
 */
public record ExecutionSummary(
    String executionId,
    String sessionId,
    LocalDateTime startTime,
    LocalDateTime endTime,
    long entryCount) {}
