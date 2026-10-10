// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import reactor.core.publisher.Mono;

/**
 * Plugin interface for converting an inbound {@link ExecutionControlCommand} into a {@link
 * WorkflowDirective}.
 *
 * <p>Implementations are discovered by Spring as beans, collected into an ordered list by the
 * {@code DirectiveDispatcher}, and matched against each command. Of the processors whose {@link
 * #canProcess} returns {@code true}, the one with the highest {@link #getPriority()} handles the
 * command; the others are skipped.
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
   * @return a Mono emitting one {@link WorkflowDirective} for the dispatcher to apply, an empty
   *     Mono when the processor has already carried out the command itself (as the built-in
   *     processors do), or an error Mono
   */
  Mono<WorkflowDirective> process(ExecutionControlCommand command);

  /**
   * Relative priority used to order processors when multiple match.
   *
   * <p>The highest value wins. Defaults to {@code 0}; built-in processors use 10 to 20, so a custom
   * processor must return more than the built-in it replaces (above 20 to override any).
   *
   * @return the priority value
   */
  default int getPriority() {
    return 0;
  }
}
