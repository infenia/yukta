// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

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
   * @param event the event
   * @return the decision; an error counts as no opinion
   */
  Mono<ControlDecision> decide(ExecutionEvent event);

  @Override
  default Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return events
        .concatMap(event -> decide(event).onErrorResume(_ -> Mono.empty()))
        .concatMapIterable(ControlDecision::commands);
  }
}
