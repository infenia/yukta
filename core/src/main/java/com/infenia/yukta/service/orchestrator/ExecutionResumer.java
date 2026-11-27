// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
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
 * checkpoints ({@code yukta.restart.store-type=postgres}). A run can be resumed once, against the
 * workflow version it started with (the current one, or a saved version with the same graph); the
 * new run can in turn be resumed if it is aborted too. Each resume counts itself in the payload
 * ({@link #RESUMES_KEY}), so automatic resumes can stop a run that keeps taking the server down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class ExecutionResumer {

  /** The reserved payload key that counts how many resumes led to a run. */
  public static final String RESUMES_KEY = "yukta.resumes";

  /**
   * The reserved payload key that marks, in the log, a run whose workflow is not durable: such a
   * run is never resumed, whatever its workflow says by then.
   */
  public static final String NOT_DURABLE_KEY = "yukta.notDurable";

  /**
   * How many saved versions a resume looks through for the one a changed workflow ran with; the
   * stores keep 20 per workflow. Each one looked at is prepared, so this bounds the cost.
   */
  private static final int REVISIONS_SEARCHED = 20;

  private static final String EXECUTION_PREFIX = "Execution ";

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
    return resume(executionId, Integer.MAX_VALUE, false);
  }

  /**
   * Resumes an aborted run on its own (auto-resume), unless it came from {@code maxResumes} resumes
   * already or is a sub-workflow run. A sub-workflow run is left to its parent: the resumed parent
   * starts it again, and a resumed child would run detached, its results reaching nobody.
   *
   * @param executionId the aborted run
   * @param maxResumes how many resumes may lead to the run; one more is refused
   * @return the id of the new run
   * @throws NotFoundException if the run is unknown
   * @throws ConflictException if it cannot be resumed
   */
  public Mono<String> resume(final String executionId, final int maxResumes) {
    return resume(executionId, maxResumes, true);
  }

  private Mono<String> resume(
      final String executionId, final int maxResumes, final boolean automatic) {
    return Mono.defer(() -> {
      // One resume at a time per run: the log only shows RUN_RESUMED once it has started.
      if (!resuming.add(executionId)) {
        return Mono.error(
            new ConflictException(
                EXECUTION_PREFIX + executionId + " is being resumed or was already resumed"));
      }
      // A claim is dropped when the resume fails or is cancelled (for example a client that
      // gave up while the workflow was being prepared); the log keeps the real record.
      // reads the whole log and the checkpoint files, so not on the caller's (event loop)
      // thread
      return doResume(executionId, maxResumes, automatic)
          .subscribeOn(Schedulers.boundedElastic())
          .doOnError(_ -> resuming.remove(executionId))
          .doOnCancel(() -> resuming.remove(executionId));
    });
  }

  private Mono<String> doResume(
      final String executionId, final int maxResumes, final boolean automatic) {
    return executionLog
        .forExecution(executionId)
        .collectList()
        .flatMap(events -> {
          final ExecutionLogEvent started = first(events, ExecutionLogEvent.Type.RUN_STARTED);
          if (started == null) {
            return Mono.error(new NotFoundException("Execution not found: " + executionId));
          }
          final ConflictException refusal =
              Refusals.refusalOf(events, started, maxResumes, automatic);
          if (refusal != null) {
            return Mono.error(refusal);
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
              .flatMap(current -> definitionOf(started, current))
              .flatMap(found ->
                  admissionKeys
                      .claim(found.definition(), Objects.requireNonNull(started.payload()))
                      .map(java.util.Optional::of)
                      .defaultIfEmpty(java.util.Optional.empty())
                      .flatMap(claim ->
                          Flux.fromIterable(found.prepared().topologicalOrder())
                              // The store's durable marker decides, not the log: a node end in the
                              // log is only queued for the writer and can be lost in a crash.
                              .map(WorkflowNode::nodeId)
                              .filterWhen(node -> checkpointStore.finishedAsync(executionId, node))
                              .collect(Collectors.toSet())
                              .flatMap(finished ->
                                  resumeFrom(
                                      started, found.prepared(), finished, claim.orElse(null)))));
        });
  }

  /** A workflow definition and its prepared graph. */
  private record Found(WorkflowDefinition definition, PreparedWorkflow prepared) {}

  /**
   * The definition the run started with: the current one if its graph is unchanged, else the newest
   * of the last {@link #REVISIONS_SEARCHED} saved versions whose graph hashes the same.
   */
  private Mono<Found> definitionOf(
      final ExecutionLogEvent started, final WorkflowDefinition current) {
    final String sessionId = started.sessionId();
    final String workflowId = started.workflowId();
    return matching(started, current)
        .switchIfEmpty(
            Mono.defer(() ->
                workflowDefinitionStore
                    .revisions(sessionId, workflowId)
                    .take(REVISIONS_SEARCHED)
                    .concatMap(revision ->
                        workflowDefinitionStore
                            .findRevision(sessionId, workflowId, revision.id())
                            .flatMap(def -> matching(started, def))
                            // a version that no longer prepares (a plugin removed) is not the one
                            .onErrorResume(_ -> Mono.empty()))
                    .next()))
        .switchIfEmpty(
            Mono.error(
                new ConflictException(
                    "The workflow changed since "
                        + started.executionId()
                        + " started, and none of its saved versions is the one it ran")));
  }

  private Mono<Found> matching(final ExecutionLogEvent started, final WorkflowDefinition def) {
    return orchestrator
        .prepareWorkflow(def)
        .filter(prepared -> DefinitionVersion.of(prepared).equals(started.definitionVersion()))
        .map(prepared -> new Found(def, prepared));
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
        new java.util.LinkedHashMap<String, Object>(Objects.requireNonNull(started.payload()));
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

  /**
   * The start payload as the log keeps it: marked when the workflow is not durable, so the run is
   * never resumed even if the workflow is made durable later.
   *
   * @param prepared the workflow the run uses
   * @param payload the start payload
   * @return the payload to log
   */
  public static Map<String, Object> logged(
      final PreparedWorkflow prepared, final Map<String, Object> payload) {
    if (prepared.durable()) {
      return payload;
    }
    final var marked = new java.util.TreeMap<String, Object>();
    marked.putAll(payload);
    marked.put(NOT_DURABLE_KEY, true);
    return marked;
  }

  /** How many resumes led to the run that started with this payload. */
  private static int resumesOf(final Map<String, Object> payload) {
    return payload.get(RESUMES_KEY) instanceof Number count ? count.intValue() : 0;
  }

  private static ExecutionLogEvent first(
      final List<ExecutionLogEvent> events, final ExecutionLogEvent.Type type) {
    return events.stream().filter(event -> event.type() == type).findFirst().orElse(null);
  }

  /** The reasons a run cannot be resumed; each check keeps the order of the refusals. */
  private static final class Refusals {

    /**
     * Why the run cannot be resumed now.
     *
     * @return the refusal, or null when the run can be resumed
     */
    private static @Nullable ConflictException refusalOf(
        final List<ExecutionLogEvent> events,
        final ExecutionLogEvent started,
        final int maxResumes,
        final boolean automatic) {
      final String executionId = started.executionId();
      final ExecutionLogEvent resumed = first(events, ExecutionLogEvent.Type.RUN_RESUMED);
      if (resumed != null) {
        return new ConflictException(
            EXECUTION_PREFIX + executionId + " was already resumed as " + resumed.status());
      }
      if (first(events, ExecutionLogEvent.Type.RUN_ABORTED) == null) {
        return new ConflictException("Only an aborted run can be resumed: " + executionId);
      }
      final Map<String, Object> payload = started.payload();
      if (payload == null) {
        return new ConflictException("The payload of " + executionId + " was not recorded");
      }
      if (Boolean.TRUE.equals(payload.get(NOT_DURABLE_KEY))) {
        return new ConflictException(
            EXECUTION_PREFIX
                + executionId
                + " cannot be resumed: its workflow was not durable (durable: false) when"
                + " it started");
      }
      if (automatic && payload.containsKey(SubWorkflowGateway.PARENT_KEY)) {
        return new ConflictException(
            EXECUTION_PREFIX
                + executionId
                + " is a sub-workflow run; it is run again through its parent");
      }
      final int resumes = resumesOf(payload);
      if (resumes >= maxResumes) {
        return new ConflictException(
            EXECUTION_PREFIX
                + executionId
                + " was resumed "
                + resumes
                + " times already; it is left to be resumed by hand");
      }
      return null;
    }
  }
}
