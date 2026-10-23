// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A decision maker on the control bus that judges one event at a time. Implement {@link #decide};
 * hosting, auditing and rate limiting are those of any {@link ExecutionSupervisor}.
 */
public interface ControlPlanePlugin extends ExecutionSupervisor {

  /**
   * Decides what to do about an event.
   *
   * <p>The returned {@code Mono} should complete in bounded time: the host handles one event at a
   * time per plugin, so one that never ends would stall all later events. The host therefore stops
   * waiting after {@link #decisionTimeout()} and treats the event as having no decision. External
   * calls should still apply their own, shorter timeout.
   *
   * @param event the event
   * @return the decision; an error counts as no opinion
   */
  Mono<ControlDecision> decide(ExecutionEvent event);

  /**
   * Longest the host waits for {@link #decide} on one event; a slower decision counts as no opinion
   * and the host moves on to the next event.
   *
   * @return the limit, 10 seconds by default
   */
  default Duration decisionTimeout() {
    return Duration.ofSeconds(10);
  }

  @Override
  default Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return events
        .concatMap(
            event ->
                decide(event)
                    .timeout(decisionTimeout(), Mono.empty())
                    .onErrorResume(_ -> Mono.empty()))
        .concatMapIterable(ControlDecision::commands);
  }
}
