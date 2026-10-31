// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Starts workflow executions: loads the definition, refuses what the scheduler cannot take,
 * prepares the workflow once however many starts ask for it at the same time, and runs it.
 */
@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkflowStarter {

  /** Start payload key that pins a run to a saved version of the workflow. */
  /* default */ static final String PIN_KEY = "yukta.version";

  /** The stored workflow definitions. */
  private final WorkflowDefinitionStore definitions;

  /** Prepared workflows, so a repeated start does not prepare again. */
  private final PreparedWorkflowCache cache;

  /** Prepares and runs workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** Refuses starts that could neither run nor be queued. */
  private final ExecutionScheduler scheduler;

  /** Makes starts safe to retry. */
  private final StartIdempotency idempotency;

  /** Preparations in flight, so concurrent starts of one workflow prepare it once. */
  private final Map<PrepareKey, Mono<PreparedWorkflow>> preparing = new ConcurrentHashMap<>();

  /* default */ Mono<String> start(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    return startOnce(sessionId, workflowId, payload);
  }

  /* default */ Mono<String> start(
      final String sessionId,
      final String workflowId,
      final Map<String, Object> payload,
      final String idempotencyKey) {
    return idempotency.once(
        sessionId + "\0" + workflowId,
        idempotencyKey,
        () -> startOnce(sessionId, workflowId, payload));
  }

  /** The version a start asks for with the payload key {@code yukta.version}, or null. */
  private static String pinnedVersion(final Map<String, Object> payload) {
    final Object version = payload == null ? null : payload.get(PIN_KEY);
    return version instanceof final String text && !text.isBlank() ? text : null;
  }

  private Mono<String> startOnce(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    log.atInfo()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("workflowId", workflowId)
        .log("Starting workflow execution");

    if (cache.isDeleting(sessionId, workflowId)) {
      return Mono.error(
          new ConflictException(
              "Workflow " + workflowId + " in session " + sessionId + " is being deleted"));
    }
    final String pinned = pinnedVersion(payload);
    return (pinned == null
            ? definitions.find(sessionId, workflowId)
            : definitions.findRevision(sessionId, workflowId, pinned))
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.atWarn()
                      .addKeyValue("sessionId", sessionId)
                      .addKeyValue("workflowId", workflowId)
                      .log("Workflow definition not found");
                  return Mono.error(
                      new NotFoundException(
                          "Workflow not found for session: "
                              + sessionId
                              + ", workflow: "
                              + workflowId));
                }))
        .flatMap(
            def -> {
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflowId", workflowId)
                  .addKeyValue("nodeCount", def.nodes().size())
                  .addKeyValue("edgeCount", def.edges().size())
                  .log("Workflow definition found and loaded");

              // Refuse at once, with a reason, a start that could neither run nor be queued.
              scheduler.assertCanAccept(sessionId, workflowId);
              final String executionId = UUID.randomUUID().toString();
              return prepareAndExecute(
                  sessionId, workflowId, def, executionId, payload, pinned == null);
            })
        .doOnSuccess(
            executionId ->
                log.atInfo()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("executionId", executionId)
                    .log("Workflow execution started successfully"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .log("Failed to start workflow execution"));
  }

  /** Prepares a workflow once even when several starts ask for it at the same time. */
  private Mono<PreparedWorkflow> prepareShared(
      final String sessionId,
      final String workflowId,
      final WorkflowDefinition def,
      final String executionId) {
    // The key holds the definition itself (a record, compared by content), so a start with a
    // newer definition never joins the preparation of an older one.
    final PrepareKey key = new PrepareKey(sessionId, workflowId, def);
    return preparing.computeIfAbsent(
        key,
        _ ->
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    prepared ->
                        // Cache only if the definition is still the current one: a replace that
                        // happened while preparing has already invalidated the cache.
                        definitions
                            .find(sessionId, workflowId)
                            .filter(def::equals)
                            .doOnNext(
                                _ -> {
                                  log.atDebug()
                                      .addKeyValue("executionId", executionId)
                                      .log("Workflow prepared and caching");
                                  cache.put(sessionId, workflowId, prepared, def);
                                })
                            .thenReturn(prepared))
                .doFinally(_ -> preparing.remove(key))
                .cache());
  }

  private Mono<String> prepareAndExecute(
      final String sessionId,
      final String workflowId,
      final WorkflowDefinition def,
      final String executionId,
      final Map<String, Object> payload,
      final boolean useCache) {
    return Mono.defer(
            () -> {
              log.atDebug()
                  .addKeyValue("sessionId", sessionId)
                  .addKeyValue("workflowId", workflowId)
                  .addKeyValue("executionId", executionId)
                  .log("Preparing workflow for execution");

              // A run pinned to an older version must not take the current prepared workflow.
              final java.util.Optional<PreparedWorkflow> cached =
                  useCache ? cache.get(sessionId, workflowId, def) : java.util.Optional.empty();
              if (cached.isPresent()) {
                log.atDebug()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("workflowId", workflowId)
                    .addKeyValue("executionId", executionId)
                    .log("Using cached PreparedWorkflow");
                return Mono.just(cached.get());
              } else {
                return prepareShared(sessionId, workflowId, def, executionId);
              }
            })
        .flatMap(
            prepared -> {
              // The slot is asked for here, not inside the run, so a refusal reaches the caller
              // as an error and no execution id is handed out for a run that cannot start.
              final ExecutionScheduler.Admission admission =
                  scheduler.acquire(
                      sessionId, workflowId, ExecutionScheduler.priorityOf(payload), executionId);
              if (admission.refused()) {
                return admission.permit().thenReturn(executionId);
              }
              orchestrator
                  .execute(sessionId, workflowId, executionId, prepared, payload, admission)
                  .doOnError(
                      err ->
                          log.atError()
                              .setCause(err)
                              .addKeyValue("executionId", executionId)
                              .log("Async workflow execution failed"))
                  .onErrorComplete()
                  .subscribe();
              return Mono.just(executionId);
            });
  }

  /**
   * Two overlapping applies can finish out of order and leave the older one cached: if the stored
   * definition is no longer {@code applied}, the cached entry is dropped so the next start prepares
   * anew.
   */
  /* default */ Mono<Void> dropCacheIfStale(
      final String sessionId, final WorkflowDefinition applied) {
    return definitions
        .find(sessionId, applied.workflowId())
        .filter(applied::equals)
        .hasElement()
        .doOnNext(
            current -> {
              if (!current) {
                cache.invalidate(sessionId, applied.workflowId());
              }
            })
        .then();
  }

  /** A workflow definition being prepared; compared by content, so a newer one never joins. */
  private record PrepareKey(String sessionId, String workflowId, WorkflowDefinition def) {}
}
