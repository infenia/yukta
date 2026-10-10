// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

/** Session-ownership check shared by every execution-scoped tool. */
@UtilityClass
@SuppressWarnings("PMD.CommentDefaultAccessModifier")
class ExecutionOwnership {

  /**
   * Resolve an execution of a session. A missing execution and an execution of another session
   * yield the same error, so callers cannot probe other sessions. Reads the per-session record, so
   * finished executions stay reachable after the tracker's index entry expires.
   */
  static Mono<WorkflowProgress> require(
      final ControlBusGateway controlBus, final String sessionId, final String executionId) {
    return Mono.defer(() -> Mono.justOrEmpty(controlBus.getProgress(sessionId, executionId)))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Execution not found: "
                            + executionId
                            + ". Use get_workflow_history to list executions.")));
  }
}
