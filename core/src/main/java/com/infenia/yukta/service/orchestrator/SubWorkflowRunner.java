// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.QueueTimeoutException;
import com.infenia.yukta.service.orchestrator.compiler.ExecutionTimeoutException;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs a stored workflow as a child of another run and waits for it, collecting what reaches its
 * terminal nodes. The child is a normal run: it has its own execution id, is tracked, limited by
 * the scheduler and can be controlled. With {@code yukta.execution.max-concurrent} the child needs
 * a free slot of its own, so the limit must allow the parent and its children together.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class SubWorkflowRunner implements SubWorkflowGateway {

  /** The stored workflow definitions. */
  private final WorkflowDefinitionStore definitions;

  /** Prepared workflows. */
  private final PreparedWorkflowCache cache;

  /** Prepares and runs workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** Lends a child the slot its parent holds. */
  private final ExecutionScheduler scheduler;

  @Override
  public Mono<Result> run(
      final String sessionId,
      final String workflowId,
      final Map<String, Object> payload,
      final String parentExecutionId,
      final int depth) {
    return Mono.defer(
        () -> {
          if (depth > MAX_DEPTH) {
            return Mono.error(
                new IllegalStateException(
                    "Sub-workflows are nested more than " + MAX_DEPTH + " deep"));
          }
          final String executionId = UUID.randomUUID().toString();
          final ResultCollector collector = new ResultCollector();
          final Map<String, Object> start = new HashMap<>(payload);
          start.put(DEPTH_KEY, depth);
          start.put(PARENT_KEY, parentExecutionId);
          return prepared(sessionId, workflowId)
              .flatMap(
                  prepared ->
                      orchestrator
                          .execute(
                              sessionId,
                              workflowId,
                              executionId,
                              prepared,
                              start,
                              scheduler.borrowed())
                          .contextWrite(ctx -> ctx.put("resultCollector", collector))
                          // even a grandchild's failure is reported as this child's
                          .onErrorMap(e -> new ChildFailed(executionId, failureStatus(e), e)))
              // Registering and archiving the child do file work, so not on the caller's thread.
              .subscribeOn(Schedulers.boundedElastic())
              .then(Mono.fromSupplier(() -> result(executionId, collector)));
        });
  }

  /** What the child produced, and the outcome terminal it reached, if any. */
  private static Result result(final String executionId, final ResultCollector collector) {
    final Map<String, Object> ended =
        collector.getCompletions().stream()
            .map(ResultCollector.Completion::metadata)
            .filter(metadata -> metadata.get("outcome") != null)
            .findFirst()
            .orElse(Map.of());
    final Object outcome = ended.get("outcome");
    final Object reason = ended.get("reason");
    return new Result(
        executionId,
        collector.getResults().stream().<Object>map(message -> message.payload()).toList(),
        "SUCCESS",
        outcome == null ? null : outcome.toString(),
        reason == null ? null : reason.toString());
  }

  private static String failureStatus(final Throwable error) {
    return error instanceof QueueTimeoutException || error instanceof ExecutionTimeoutException
        ? "TIMED_OUT"
        : "FAILURE";
  }

  private Mono<PreparedWorkflow> prepared(final String sessionId, final String workflowId) {
    return Mono.justOrEmpty(cache.get(sessionId, workflowId))
        .switchIfEmpty(
            Mono.defer(
                () ->
                    definitions
                        .find(sessionId, workflowId)
                        .switchIfEmpty(
                            Mono.error(
                                () ->
                                    new NotFoundException(
                                        "Workflow not found for session: "
                                            + sessionId
                                            + ", workflow: "
                                            + workflowId)))
                        .flatMap(
                            definition ->
                                orchestrator
                                    .prepareWorkflow(definition)
                                    .flatMap(
                                        prepared ->
                                            // cached only if the stored definition is still this
                                            // one
                                            definitions
                                                .find(sessionId, workflowId)
                                                .filter(definition::equals)
                                                .doOnNext(
                                                    _ -> cache.put(sessionId, workflowId, prepared))
                                                .thenReturn(prepared)))));
  }
}
