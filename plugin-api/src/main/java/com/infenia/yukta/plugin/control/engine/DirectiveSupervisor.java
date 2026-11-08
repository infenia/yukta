// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import reactor.core.publisher.Flux;

/**
 * A supervisor whose commands carry a key, a cause and a reason, so the host can check them with
 * the directive gate (initiative, reason, idempotency) before it applies them.
 */
public interface DirectiveSupervisor extends ExecutionSupervisor {

  /**
   * The commands to apply, with their cause.
   *
   * @param events the execution events
   * @return the command directives
   */
  Flux<Directive.Command> directives(Flux<ExecutionEvent> events);

  @Override
  default Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return directives(events).map(Directive.Command::command);
  }
}
