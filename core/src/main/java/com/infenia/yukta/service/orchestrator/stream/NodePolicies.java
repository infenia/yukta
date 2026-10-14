// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/** Applies a node's {@link NodePolicy} to the processing of one message. */
@Slf4j
@UtilityClass
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.CognitiveComplexity"})
public class NodePolicies {

  /** Header that tells the plugin which attempt (1, 2, ...) it is processing the message in. */
  public static final String ATTEMPT_HEADER = "attempt";

  /** Header of a routed message: how many attempts were made. */
  public static final String FAILURE_ATTEMPTS_HEADER = "failure.attempts";

  /** Header of a routed message: class of the exception that ended the last attempt. */
  public static final String FAILURE_EXCEPTION_HEADER = "failure.exception";

  /** Header of a routed message: when it failed (ISO-8601). */
  public static final String FAILURE_TIME_HEADER = "failure.time";

  /** Jitter applied to retry delays, as a fraction of the delay. */
  private static final double JITTER = 0.5;

  /**
   * Wraps the processing of one message with the node's retry and error handling.
   *
   * @param policy the node's policy
   * @param nodeId the node, used in logs and in the failure tag
   * @param message the message being processed
   * @param processing calls the plugin for a message; called once per attempt, with the message
   *     carrying an {@value #ATTEMPT_HEADER} header so the plugin can de-duplicate side effects
   * @param onAttempt called at the start of every attempt, so the node is not seen as idle
   * @return the output, retried on failure and then failed, dropped or routed per the policy
   */
  public Flux<Message<?>> apply(
      final NodePolicy policy,
      final String nodeId,
      final Message<?> message,
      final Function<Message<?>, Flux<Message<?>>> processing,
      final Runnable onAttempt) {
    return apply(policy, nodeId, message, processing, onAttempt, null);
  }

  /**
   * Wraps the processing of one message with the node's retry and error handling, counting the
   * failures it drops or routes against the node's failure budget.
   *
   * @param policy the node's policy
   * @param nodeId the node, used in logs and in the failure tag
   * @param message the message being processed
   * @param processing calls the plugin for a message; called once per attempt
   * @param onAttempt called at the start of every attempt, so the node is not seen as idle
   * @param ledger the node's failures in this run, or null when the policy has no budget
   * @return the output, retried on failure and then failed, dropped or routed per the policy
   */
  public Flux<Message<?>> apply(
      final NodePolicy policy,
      final String nodeId,
      final Message<?> message,
      final Function<Message<?>, Flux<Message<?>>> processing,
      final Runnable onAttempt,
      @Nullable final FailureLedger ledger) {
    final AtomicInteger attempts = new AtomicInteger();
    // Every attempt counts as activity, so a retrying node is not seen as idle.
    Flux<Message<?>> result =
        Flux.defer(
            () -> {
              onAttempt.run();
              return processing.apply(
                  message.withHeader(ATTEMPT_HEADER, attempts.incrementAndGet()));
            });
    if (policy.elementTimeout() != null) {
      result = result.timeout(policy.elementTimeout());
    }
    final NodePolicy.Retry retry = policy.retry();
    final boolean retries = retry != null && retry.maxAttempts() > 1;
    if (retries || policy.onError() != NodePolicy.OnError.FAIL) {
      // A message either produces all its output or none: what an attempt emitted before it
      // failed is not sent on, so a retry or a dead letter never comes on top of a partial result.
      result = result.collectList().flatMapMany(Flux::fromIterable);
    }
    if (retries) {
      result =
          result.retryWhen(
              Retry.backoff(retry.maxAttempts() - 1L, retry.backoff())
                  .maxBackoff(retry.maxBackoff())
                  .jitter(JITTER)
                  .filter(error -> !RunAbortedException.isAbort(error) && retry.shouldRetry(error))
                  .doBeforeRetry(
                      signal ->
                          log.atWarn()
                              .addKeyValue("nodeId", nodeId)
                              .addKeyValue("attempt", signal.totalRetries() + 2)
                              .setCause(signal.failure())
                              .log("Retrying failed message"))
                  .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }
    return switch (policy.onError()) {
      case FAIL -> result;
      case CONTINUE ->
          result.onErrorResume(
              error -> {
                if (RunAbortedException.isAbort(error)) {
                  return Flux.error(error);
                }
                log.atWarn()
                    .addKeyValue("nodeId", nodeId)
                    .setCause(error)
                    .log("Dropping failed message (onError=CONTINUE)");
                return overBudget(ledger) ? budgetError(nodeId, policy, error) : Flux.empty();
              });
      case ROUTE ->
          result.onErrorResume(
              error ->
                  RunAbortedException.isAbort(error)
                      ? Flux.error(error)
                      : overBudget(ledger)
                          ? budgetError(nodeId, policy, error)
                          : Flux.just(deadLetter(message, nodeId, error, attempts.get())));
    };
  }

  private boolean overBudget(@Nullable final FailureLedger ledger) {
    return ledger != null && ledger.record();
  }

  private Flux<Message<?>> budgetError(
      final String nodeId, final NodePolicy policy, final Throwable last) {
    final var budget = Objects.requireNonNull(policy.failureBudget());
    return Flux.error(
        new IllegalStateException(
            "Node '"
                + nodeId
                + "' failed more than "
                + budget.maxFailures()
                + " times within "
                + budget.window()
                + "; last failure: "
                + last,
            last));
  }

  /** The failing message tagged with where, why, how often and when it failed. */
  private Message<?> deadLetter(
      final Message<?> message, final String nodeId, final Throwable error, final int attempts) {
    return message
        .withFailure(nodeId, "PROCESSING_FAILED", String.valueOf(error))
        .withHeader(FAILURE_ATTEMPTS_HEADER, attempts)
        .withHeader(FAILURE_EXCEPTION_HEADER, error.getClass().getName())
        .withHeader(FAILURE_TIME_HEADER, Instant.now().toString())
        .withSourcePort(NodePolicy.ERROR_PORT);
  }
}
