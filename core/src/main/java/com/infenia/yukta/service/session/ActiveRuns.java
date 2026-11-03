// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.time.Duration;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Refuses or cancels the active runs of a session before it or a workflow is deleted. */
@UtilityClass
class ActiveRuns {

  /** How long a forced delete waits for cancelled runs to end. */
  private static final Duration FORCE_WAIT = Duration.ofSeconds(30);

  /** How often a forced delete looks whether the cancelled runs have ended. */
  private static final Duration FORCE_POLL = Duration.ofMillis(250);

  /**
   * Makes sure no run of the session (or of one workflow) is going: refuses, or with {@code force}
   * cancels them and waits a while for them to end.
   */
  /* default */ static Mono<Void> clear(
      final ControlBusGateway controlBus,
      final String sessionId,
      @Nullable final String workflowId,
      final boolean force) {
    return active(controlBus, sessionId, workflowId)
        .flatMap(
            runs -> {
              if (runs.isEmpty()) {
                return Mono.<Void>empty();
              }
              if (!force) {
                return Mono.error(new ActiveRunsException(ids(runs), false));
              }
              return Flux.fromIterable(
                      runs.stream().map(WorkflowExecutionSummary::workflowId).distinct().toList())
                  .concatMap(
                      id -> controlBus.stopWorkflow(sessionId, id, "Deleted", StopMode.CANCEL))
                  .then(awaitEnded(controlBus, sessionId, workflowId));
            });
  }

  private static Mono<Void> awaitEnded(
      final ControlBusGateway controlBus,
      final String sessionId,
      @Nullable final String workflowId) {
    return Flux.interval(FORCE_POLL)
        .concatMap(_ -> active(controlBus, sessionId, workflowId))
        .filter(List::isEmpty)
        .next()
        .timeout(
            FORCE_WAIT,
            active(controlBus, sessionId, workflowId)
                .flatMap(
                    left ->
                        Mono.<List<WorkflowExecutionSummary>>error(
                            new ActiveRunsException(ids(left), true))))
        .then();
  }

  private static List<String> ids(final List<WorkflowExecutionSummary> runs) {
    return runs.stream().map(WorkflowExecutionSummary::executionId).toList();
  }

  /** The runs of the session (or one workflow) that have not ended. */
  private static Mono<List<WorkflowExecutionSummary>> active(
      final ControlBusGateway controlBus,
      final String sessionId,
      @Nullable final String workflowId) {
    return controlBus
        .getHistory(sessionId)
        .map(
            history ->
                history.stream()
                    .filter(run -> !run.status().isTerminal())
                    .filter(run -> workflowId == null || workflowId.equals(run.workflowId()))
                    .toList());
  }
}
