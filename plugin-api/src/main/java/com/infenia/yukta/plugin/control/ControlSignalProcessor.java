// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import reactor.core.publisher.Mono;

/**
 * Plugin interface for converting an inbound {@link ExecutionControlCommand} into a {@link
 * WorkflowDirective}.
 *
 * <p>Implementations are discovered by Spring as beans, collected into an ordered list by the
 * {@code DirectiveDispatcher}, and evaluated in priority order. The first processor whose {@link
 * #canProcess} returns {@code true} handles the command; subsequent processors are skipped.
 *
 * <p>Register a custom processor to intercept commands before the defaults fire – for example, to
 * convert a RESTART into a RESTART_FROM_NODE when specific nodes need recovery, or to add audit
 * logging.
 */
public interface ControlSignalProcessor {

  /**
   * Returns {@code true} if this processor can handle the given command.
   *
   * @param command the inbound execution control command
   * @return {@code true} when this processor should be invoked
   */
  boolean canProcess(ExecutionControlCommand command);

  /**
   * Converts the command into the directive that the dispatcher will apply.
   *
   * @param command the inbound execution control command
   * @return a Mono emitting exactly one {@link WorkflowDirective}, or an error Mono
   */
  Mono<WorkflowDirective> process(ExecutionControlCommand command);

  /**
   * Relative priority used to order processors when multiple match.
   *
   * <p>Higher values run first. Defaults to {@code 0}; built-in processors use {@code 0} so any
   * custom processor with a positive value takes precedence.
   *
   * @return the priority value
   */
  default int getPriority() {
    return 0;
  }
}
