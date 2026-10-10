// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/** Applies a node's {@link NodePolicy} to the processing of one message. */
@Slf4j
@UtilityClass
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class NodePolicies {

  /** Header that tells the plugin which attempt (1, 2, ...) it is processing the message in. */
  public static final String ATTEMPT_HEADER = "attempt";

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
    if (retry != null && retry.maxAttempts() > 1) {
      result =
          result.retryWhen(
              Retry.backoff(retry.maxAttempts() - 1L, retry.backoff())
                  .maxBackoff(retry.maxBackoff())
                  .jitter(JITTER)
                  .filter(error -> !(error instanceof IllegalArgumentException))
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
                log.atWarn()
                    .addKeyValue("nodeId", nodeId)
                    .setCause(error)
                    .log("Dropping failed message (onError=CONTINUE)");
                return Flux.empty();
              });
      case ROUTE ->
          result.onErrorResume(
              error ->
                  Flux.just(
                      message
                          .withFailure(nodeId, "PROCESSING_FAILED", String.valueOf(error))
                          .withSourcePort(NodePolicy.ERROR_PORT)));
    };
  }
}
