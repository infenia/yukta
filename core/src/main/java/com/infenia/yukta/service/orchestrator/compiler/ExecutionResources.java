// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import com.infenia.yukta.service.orchestrator.stream.NodeActivity;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Runs the assembled terminals under the session's execution timeout and releases resources. */
@Slf4j
@UtilityClass
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class ExecutionResources {

  /** Default timeout in seconds when the execution has no session. */
  private static final long DEFAULT_TIMEOUT = 3600L;

  /**
   * Runs all terminals together, starting the connectors on subscription, and disposes every
   * registered disposable when the run completes, fails, times out or is cancelled.
   *
   * @param configStore source of the session's execution timeout
   * @param scheduler scheduler used for the timeout
   * @param sessionId the session identifier, or null to use the default timeout
   * @param terminals the terminal completions of the workflow
   * @param connectors upstream connection tasks, run in reverse order on subscription
   * @param disposables live list that connectors add their subscriptions to; disposed at the end
   * @param paused tells whether the run is paused, which holds the execution deadline
   * @return a Mono completing when all terminals completed
   */
  public Mono<Void> run(
      final SessionConfigStore configStore,
      final Scheduler scheduler,
      @Nullable final String sessionId,
      final List<Mono<Void>> terminals,
      final List<Runnable> connectors,
      final List<Disposable> disposables,
      final BooleanSupplier paused) {
    final Mono<Long> timeoutMono =
        sessionId != null ? configStore.getExecutionTimeout(sessionId) : Mono.just(DEFAULT_TIMEOUT);

    return timeoutMono.flatMap(
        timeout -> {
          final Mono<Void> all =
              terminals.isEmpty() ? Mono.empty() : Mono.whenDelayError(terminals);
          final Mono<Void> timed =
              all.timeout(
                  NodeActivity.delayUnlessPaused(Duration.ofSeconds(timeout), scheduler, paused),
                  Mono.error(() -> new ExecutionTimeoutException(timeout)));
          return Mono.using(
              () -> disposables,
              _ -> timed.doOnSubscribe(_ -> runConnectors(connectors)),
              ExecutionResources::dispose);
        });
  }

  private void runConnectors(final List<Runnable> connectors) {
    for (int i = connectors.size() - 1; i >= 0; i--) {
      try {
        connectors.get(i).run();
      } catch (
          @SuppressWarnings("PMD.AvoidCatchingGenericException")
          final Exception e) {
        log.atError().addKeyValue("connectorIndex", i).setCause(e).log("Error executing connector");
      }
    }
  }

  private void dispose(final List<Disposable> disposables) {
    for (final Disposable disposable : List.copyOf(disposables)) {
      try {
        disposable.dispose();
      } catch (
          @SuppressWarnings("PMD.AvoidCatchingGenericException")
          final Exception e) {
        log.atError().setCause(e).log("Error disposing resource");
      }
    }
  }
}
