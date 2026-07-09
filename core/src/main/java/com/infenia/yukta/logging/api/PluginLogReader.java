// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for reading plugin log entries from backend storage.
 *
 * <p>Implementations should provide reactive, non-blocking access to logs.
 */
public interface PluginLogReader {

  /**
   * Read all log entries for an execution.
   *
   * @param executionId the execution identifier
   * @return flux of log entries
   */
  Flux<PluginLogEntry> readExecution(String executionId);

  /**
   * Read all log entries for a session.
   *
   * @param sessionId the session identifier
   * @return flux of log entries
   */
  Flux<PluginLogEntry> readSession(String sessionId);

  /**
   * List all executions with log data for a session.
   *
   * @param sessionId the session identifier
   * @return mono containing list of execution summaries
   */
  Mono<List<ExecutionSummary>> listExecutions(String sessionId);

  /**
   * Read raw log content as a single string.
   *
   * @param executionId the execution identifier
   * @return mono containing raw log content
   */
  Mono<String> getRawContent(String executionId);
}
