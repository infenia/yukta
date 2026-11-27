// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.archive.DefinitionVersion;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.runtime.AdmissionKeys;
import com.infenia.yukta.service.runtime.ExecutionScheduler;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Continues a run that a server restart aborted.
 *
 * <p>Nodes that ended successfully (the checkpoint store holds a durable end marker) with a
 * complete recorded output are not run again: their output is replayed to the nodes that did not
 * finish. This needs the PostgreSQL execution log ({@code yukta.session.store-type=postgres}) and
 * checkpoints ({@code yukta.restart.store-type=postgres}). A run can be resumed once, and only if
 * its workflow definition is unchanged; the new run can in turn be resumed if it is aborted too.
 * Each resume counts itself in the payload ({@link #RESUMES_KEY}), so automatic resumes can stop a
 * run that keeps taking the server down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class ExecutionResumer {

  /** The reserved payload key that counts how many resumes led to a run. */
  public static final String RESUMES_KEY = "yukta.resumes";

  /** The execution log: start, payload, finished nodes, end. */
  private final ExecutionLogStore executionLog;

  /** The recorded node outputs. */
  private final NodeCheckpointStore checkpointStore;

  /** The current workflow definitions. */
  private final WorkflowDefinitionStore workflowDefinitionStore;

  /** Prepares and runs the resumed execution. */
  private final WorkflowOrchestrator orchestrator;

  /** Admits the resumed execution like any other start. */
  private final ExecutionScheduler scheduler;

  /** Works out which concurrency key a resumed run claims. */
  private final AdmissionKeys admissionKeys;

  /** Runs being resumed or already resumed by this process; a claim is kept once it succeeded. */
  private final Set<String> resuming = ConcurrentHashMap.newKeySet();

  /**
   * Resumes an aborted run as a new run.
   *
   * @param executionId the aborted run
   * @return the id of the new run
   * @throws NotFoundException if the run is unknown
   * @throws ConflictException if it cannot be resumed
   */
  public Mono<String> resume(final String executionId) {
    return resume(executionId, Integer.MAX_VALUE);
  }

  /**
   * Resumes an aborted run as a new run, unless it came from {@code maxResumes} resumes already.
   *
   * @param executionId the aborted run
   * @param maxResumes how many resumes may lead to the run; one more is refused
   * @return the id of the new run
   * @throws NotFoundException if the run is unknown
   * @throws ConflictException if it cannot be resumed
   */
  public Mono<String> resume(final String executionId, final int maxResumes) {
    return Mono.defer(() -> {
      // One resume at a time per run: the log only shows RUN_RESUMED once it has started.
      if (!resuming.add(executionId)) {
        return Mono.error(
            new ConflictException(
                "Execution " + executionId + " is being resumed or was already resumed"));
      }
      // A claim is dropped when the resume fails or is cancelled (for example a client that
      // gave up while the workflow was being prepared); the log keeps the real record.
      // reads the whole log and the checkpoint files, so not on the caller's (event loop)
      // thread
      return doResume(executionId, maxResumes)
          .subscribeOn(Schedulers.boundedElastic())
          .doOnError(_ -> resuming.remove(executionId))
          .doOnCancel(() -> resuming.remove(executionId));
    });
  }

  private Mono<String> doResume(final String executionId, final int maxResumes) {
    return executionLog
        .forExecution(executionId)
        .collectList()
        .flatMap(events -> {
          final ExecutionLogEvent started = first(events, ExecutionLogEvent.Type.RUN_STARTED);
          if (started == null) {
            return Mono.error(new NotFoundException("Execution not found: " + executionId));
          }
          final ExecutionLogEvent resumed = first(events, ExecutionLogEvent.Type.RUN_RESUMED);
          if (resumed != null) {
            return Mono.error(
                new ConflictException(
                    "Execution " + executionId + " was already resumed as " + resumed.status()));
          }
          if (first(events, ExecutionLogEvent.Type.RUN_ABORTED) == null) {
            return Mono.error(
                new ConflictException("Only an aborted run can be resumed: " + executionId));
          }
          if (started.payload() == null) {
            return Mono.error(
                new ConflictException("The payload of " + executionId + " was not recorded"));
          }
          final int resumes = resumesOf(Objects.requireNonNull(started.payload()));
          if (resumes >= maxResumes) {
            return Mono.error(
                new ConflictException(
                    "Execution "
                        + executionId
                        + " was resumed "
                        + resumes
                        + " times already; it is left to be resumed by hand"));
          }
          return workflowDefinitionStore
              .find(started.sessionId(), started.workflowId())
              .switchIfEmpty(
                  Mono.error(
                      new NotFoundException(
                          "Workflow no longer exists: "
                              + started.sessionId()
                              + "/"
                              + started.workflowId())))
              .flatMap(def ->
                  admissionKeys
                      .claim(def, Objects.requireNonNull(started.payload()))
                      .map(java.util.Optional::of)
                      .defaultIfEmpty(java.util.Optional.empty())
                      .flatMap(claim ->
                          orchestrator
                              .prepareWorkflow(def)
                              .flatMap(prepared -> start(started, prepared, claim.orElse(null)))));
        });
  }

  private Mono<String> start(
      final ExecutionLogEvent started,
      final PreparedWorkflow prepared,
      final ExecutionScheduler.@Nullable KeyClaim claim) {
    if (!DefinitionVersion.of(prepared).equals(started.definitionVersion())) {
      return Mono.error(
          new ConflictException(
              "The workflow changed since " + started.executionId() + " started"));
    }
    // The store's durable marker decides, not the log: the log record of a node end is only queued
    // for the writer and can be lost in a crash.
    return Flux.fromIterable(prepared.topologicalOrder())
        .map(WorkflowNode::nodeId)
        .filterWhen(node -> checkpointStore.finishedAsync(started.executionId(), node))
        .collect(Collectors.toSet())
        .flatMap(finished -> resumeFrom(started, prepared, finished, claim));
  }

  private Mono<String> resumeFrom(
      final ExecutionLogEvent started,
      final PreparedWorkflow prepared,
      final Set<String> finished,
      final ExecutionScheduler.@Nullable KeyClaim claim) {
    final RestartPlan plan = RestartPlan.resuming(prepared, finished);
    if (plan.rerun().isEmpty()) {
      return Mono.error(
          new ConflictException("Every node of " + started.executionId() + " had finished"));
    }
    final String newId = UUID.randomUUID().toString();
    final var payload =
        new java.util.HashMap<String, Object>(Objects.requireNonNull(started.payload()));
    payload.put(RESUMES_KEY, resumesOf(payload) + 1);
    return Flux.fromIterable(plan.boundary())
        .concatMap(node ->
            checkpointStore
                .getAll(started.executionId(), node)
                .collectList()
                .map(messages -> Map.<String, List<Message<?>>>entry(node, messages)))
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .flatMap(outputs -> {
          // The slot is taken before the log says the run was resumed, so a refused resume
          // can be tried again.
          final ExecutionScheduler.Admission admission =
              scheduler.acquireRerun(
                  started.sessionId(),
                  started.workflowId(),
                  ExecutionScheduler.priorityOf(payload),
                  newId,
                  claim);
          if (admission.refused()) {
            return admission.permit().then();
          }
          try {
            executionLog.append(ExecutionLogEvent.resumed(started, newId));
          } catch (RuntimeException e) {
            // the slot was taken above; give it back or each such failure shrinks capacity
            admission.giveBack();
            throw e;
          }
          orchestrator
              .rerun(
                  started.sessionId(),
                  started.workflowId(),
                  started.executionId(),
                  newId,
                  prepared,
                  plan,
                  payload,
                  outputs,
                  "resume of " + started.executionId(),
                  admission)
              .subscribeOn(Schedulers.boundedElastic())
              .doOnError(err ->
                  log.atError()
                      .setCause(err)
                      .addKeyValue("executionId", newId)
                      .log("Resumed execution failed"))
              .onErrorComplete()
              .subscribe();
          return Mono.<Void>empty();
        })
        .then(Mono.just(newId));
  }

  /** How many resumes led to the run that started with this payload. */
  private static int resumesOf(final Map<String, Object> payload) {
    return payload.get(RESUMES_KEY) instanceof Number count ? count.intValue() : 0;
  }

  private static ExecutionLogEvent first(
      final List<ExecutionLogEvent> events, final ExecutionLogEvent.Type type) {
    return events.stream().filter(event -> event.type() == type).findFirst().orElse(null);
  }
}
