// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provider for session and workflow logging operations. Handles log streaming and filtering with
 * optional regex pattern matching.
 */
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public interface LogProvider {

  /**
   * Stream session logs with optional filtering.
   *
   * @param sessionId the session identifier
   * @param workflowId optional workflow filter
   * @param executionId optional execution filter
   * @param filterPattern optional regex pattern filter
   * @return Flux of log lines
   */
  Flux<String> streamSessionLogs(
      String sessionId, String workflowId, String executionId, String filterPattern);

  /**
   * Get workflow execution logs.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @param filterPattern optional regex pattern filter
   * @return Mono containing formatted logs
   */
  Mono<String> getWorkflowExecutionLogs(String sessionId, String executionId, String filterPattern);
}
