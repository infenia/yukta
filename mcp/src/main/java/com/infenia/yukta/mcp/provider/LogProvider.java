// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ExecutionLogs;
import reactor.core.publisher.Mono;

/** Provider for workflow execution logs backed by the persistent plugin log store. */
@FunctionalInterface
public interface LogProvider {

  /**
   * Get the logs of a workflow execution.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param tailLines optional maximum number of trailing lines to return
   * @param filterPattern optional regex applied to each formatted log line
   * @return Mono containing the execution logs; errors if the execution is unknown or the pattern
   *     is invalid
   */
  Mono<ExecutionLogs> getExecutionLogs(
      String sessionId, String executionId, Integer tailLines, String filterPattern);
}
