// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.service.result.ExecutionResults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads what reached the terminal nodes of an execution, with the same session-ownership check as
 * the web layer's results endpoint.
 */
@Component
@RequiredArgsConstructor
public class ResultProvider {

  /** Records the results. */
  private final ExecutionResultService results;

  /** Gateway used to resolve execution ownership. */
  private final ControlBusGateway controlBus;

  /**
   * Get the results recorded for an execution so far.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param afterCursor the {@code nextCursor} of the previous call, or null for everything kept
   * @return Mono containing the results; errors if the execution is unknown
   */
  public Mono<ExecutionResults> getExecutionResult(
      final String sessionId, final String executionId, final Long afterCursor) {
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .map(_ -> results.read(executionId, afterCursor == null ? 0 : afterCursor));
  }
}
