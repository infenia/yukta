// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves an execution for a request. When the path names a session the execution must belong to
 * it, so one session's URL cannot read or control another session's runs; a missing execution and a
 * foreign one give the same {@link NotFoundException}.
 */
@Component
@RequiredArgsConstructor
public class ExecutionAccess {

  /** Gateway used to look executions up. */
  private final ControlBusGateway controlBus;

  /**
   * Resolve an execution.
   *
   * @param sessionId the session from the path, or null when the path has none
   * @param executionId the execution identifier
   * @return the execution's progress; errors with {@link NotFoundException} when it is unknown or
   *     belongs to another session
   */
  public Mono<WorkflowProgress> require(final String sessionId, final String executionId) {
    return Mono.defer(
            () ->
                Mono.justOrEmpty(
                    sessionId == null
                        ? controlBus.getCurrentProgress(executionId)
                        : controlBus.getProgress(sessionId, executionId)))
        .switchIfEmpty(
            Mono.error(() -> new NotFoundException("Execution not found: " + executionId)));
  }
}
