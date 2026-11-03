// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import com.infenia.yukta.service.orchestrator.SubWorkflowRunner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Runs the workflow of a supervisor. A run is a child run (it takes no slot of the normal pool, so
 * a saturated server still runs its supervisors) that holds one of {@code
 * yukta.supervisor.reserved-slots} slots of its own (default 4), so supervisors cannot starve
 * normal work either. A caller waits for a slot at most as long as the run may take.
 *
 * <p>A run carries {@code yukta.supervisor.depth}, {@code yukta.supervisor.id} and the id of the
 * run it came from; supervisors nested more than {@code yukta.supervisor.max-depth} (default 2)
 * deep are refused.
 */
@Slf4j
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.UseObjectForClearerAPI"})
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shared Spring beans, held on purpose")
@Component
public class SupervisorWorkflowRunner {

  /** How often a caller looks for a free slot. */
  private static final Duration SLOT_POLL = Duration.ofMillis(50);

  /** Where runs are started. */
  private final SubWorkflowRunner runner;

  /** Finds the start payload of the run an event came from; may be {@code null}. */
  private final ExecutionControlStore runs;

  /** The reserved slots. */
  private final Semaphore slots;

  /** The size of the reserved pool. */
  private final int reserved;

  /** The deepest nesting of supervisors. */
  private final int maxDepth;

  /** Counts run outcomes. */
  private final MeterRegistry meters;

  /**
   * Creates the runner.
   *
   * @param runner starts the child run
   * @param runs finds the start payload of an event's run
   * @param meters the meters
   * @param reservedSlots the size of the reserved pool
   * @param maxDepth the deepest nesting of supervisors
   */
  public SupervisorWorkflowRunner(
      final SubWorkflowRunner runner,
      final ExecutionControlStore runs,
      final MeterRegistry meters,
      @Value("${yukta.supervisor.reserved-slots:4}") final int reservedSlots,
      @Value("${yukta.supervisor.max-depth:2}") final int maxDepth) {
    this.runner = runner;
    this.runs = runs;
    this.meters = meters;
    this.reserved = reservedSlots;
    this.maxDepth = maxDepth;
    this.slots = new Semaphore(reservedSlots);
    Gauge.builder(
            "yukta.supervisor.workflow.slots.used",
            slots,
            s -> reservedSlots - s.availablePermits())
        .register(meters);
  }

  /**
   * The size of the reserved pool.
   *
   * @return the number of slots
   */
  public int reservedSlots() {
    return reserved;
  }

  /**
   * Runs a supervisor workflow once.
   *
   * @param supervisorId the binding id of the supervisor, written to the run
   * @param sessionId the internal session
   * @param workflowId the workflow
   * @param payload the start payload (event, request, ids)
   * @param eventExecutionId the run the event belongs to
   * @param timeout how long the run, and the wait for a slot, may take
   * @return how it ended; never an error
   */
  public Mono<RunResult> run(
      final String supervisorId,
      final String sessionId,
      final String workflowId,
      final Map<String, Object> payload,
      final String eventExecutionId,
      final Duration timeout) {
    return Mono.defer(
        () -> {
          final int depth = parentDepth(eventExecutionId) + 1;
          if (depth > maxDepth) {
            return counted(RunResult.none("SKIPPED"), "depth");
          }
          final Map<String, Object> start = new HashMap<>(payload);
          start.put(DefaultAnswerAuthorizer.DEPTH_KEY, depth);
          start.put(DefaultAnswerAuthorizer.STARTER_KEY, supervisorId);
          return acquire(timeout)
              .flatMap(
                  got ->
                      got
                          ? runner
                              .run(sessionId, workflowId, start, eventExecutionId, 1)
                              .timeout(timeout)
                              .map(SupervisorWorkflowRunner::result)
                              .onErrorResume(
                                  error -> Mono.just(failed(error, supervisorId, workflowId)))
                              .doFinally(_ -> slots.release())
                          : Mono.just(RunResult.none("SKIPPED")))
              .flatMap(
                  result ->
                      counted(
                          result, Objects.requireNonNullElse(result.outcome(), result.status())));
        });
  }

  private int parentDepth(final String eventExecutionId) {
    return runs.findByExecutionId(eventExecutionId)
        .map(control -> control.payload().get(DefaultAnswerAuthorizer.DEPTH_KEY))
        .filter(Number.class::isInstance)
        .map(value -> ((Number) value).intValue())
        .orElse(0);
  }

  /** Polls for a free slot until the time is up; ponytail: a waiter queue if polling shows. */
  private Mono<Boolean> acquire(final Duration timeout) {
    return Mono.fromSupplier(slots::tryAcquire)
        .filter(Boolean::booleanValue)
        .repeatWhenEmpty(repeat -> repeat.delayElements(SLOT_POLL))
        .timeout(timeout, Mono.just(false));
  }

  private static RunResult result(final SubWorkflowGateway.Result result) {
    return new RunResult("SUCCESS", result.outcome(), result.reason(), result.results());
  }

  private RunResult failed(
      final Throwable error, final String supervisorId, final String workflow) {
    final boolean timedOut = error instanceof TimeoutException;
    log.atWarn()
        .setCause(timedOut ? null : error)
        .addKeyValue("supervisor", supervisorId)
        .addKeyValue("workflow", workflow)
        .log(timedOut ? "Supervisor workflow timed out" : "Supervisor workflow failed");
    return RunResult.none(timedOut ? "TIMEOUT" : "FAILED");
  }

  private Mono<RunResult> counted(final RunResult result, final String outcome) {
    meters
        .counter("yukta.supervisor.workflow", "outcome", outcome.toLowerCase(java.util.Locale.ROOT))
        .increment();
    return Mono.just(result);
  }
}
