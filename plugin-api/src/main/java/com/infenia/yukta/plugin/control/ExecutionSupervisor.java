// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Watches workflow executions and steers them through the control bus. A rule engine, an AI agent
 * or any other decision maker plugs in by implementing this interface as a Spring bean.
 *
 * <p>The commands a supervisor emits are applied like any other control command and are recorded in
 * the execution log under its {@link #id()}. Every command, whoever sent it, can be refused by
 * {@link #review}.
 */
public interface ExecutionSupervisor {

  /**
   * Names the supervisor in the audit log. Must be unique.
   *
   * @return the id
   */
  String id();

  /**
   * Orders reviews: lower values review first.
   *
   * @return the order
   */
  default int order() {
    return 0;
  }

  /**
   * Observes the events of all executions and decides what to do about them.
   *
   * <p>Subscribed once at startup. Events caused by this supervisor's own commands are not
   * delivered back to it. A failure resubscribes with a backoff and never affects the executions.
   *
   * @param events events of all executions
   * @return the commands to apply; empty to only observe
   */
  Flux<ExecutionControlCommand> supervise(Flux<ExecutionEvent> events);

  /**
   * Approves or refuses a control command before it runs.
   *
   * @param request the command and who sent it
   * @return the verdict; an error counts as a denial
   */
  default Mono<Decision> review(final ControlRequest request) {
    return Mono.just(Decision.ALLOW);
  }
}
