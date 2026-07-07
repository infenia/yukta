/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
