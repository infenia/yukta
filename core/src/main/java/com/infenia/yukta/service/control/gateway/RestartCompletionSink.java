// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import reactor.core.publisher.Mono;

/**
 * Callback used by restart command processors to report the outcome of an in-flight restart back to
 * the {@link ControlBusGateway} caller awaiting it.
 *
 * <p>{@link #completeRestartSuccess} and {@link #completeRestartFailure} are best-effort: if the
 * caller has already timed out and the pending entry was removed, both are no-ops.
 */
public interface RestartCompletionSink {

  /**
   * Reports that a restart succeeded and the new execution has been subscribed.
   *
   * @param newExecutionId the identifier of the restarted execution
   */
  void completeRestartSuccess(String newExecutionId);

  /**
   * Reports that a restart failed before the new execution could be started.
   *
   * @param newExecutionId the identifier that was reserved for the restarted execution
   * @param error the failure
   */
  void completeRestartFailure(String newExecutionId, Throwable error);

  /**
   * Reports success once the run is subscribed and its control is registered, so a caller that gets
   * the id finds the run. A failure before that is reported as a failed restart; one after it is
   * only the run's own.
   *
   * @param newExecutionId the identifier of the restarted execution
   * @param run the restarted execution
   * @param registered whether the run's control exists yet
   * @return the run, reporting as above
   */
  default Mono<Void> reportingStart(
      final String newExecutionId, final Mono<Void> run, final BooleanSupplier registered) {
    final AtomicBoolean reported = new AtomicBoolean();
    return run.doOnSubscribe(
            _ -> {
              if (registered.getAsBoolean()) {
                reported.set(true);
                completeRestartSuccess(newExecutionId);
              }
            })
        .doOnError(
            error -> {
              if (!reported.get()) {
                completeRestartFailure(newExecutionId, error);
              }
            });
  }
}
