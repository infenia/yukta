// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/**
 * A typed action to be applied to an active workflow execution.
 *
 * <p>{@code WorkflowDirective} is the output side of the control-bus plugin pipeline. A {@link
 * ControlSignalProcessor} converts an inbound {@link
 * com.infenia.yukta.plugin.message.control.ControlCommand} into a concrete directive type. The
 * {@code DirectiveDispatcher} then pattern-matches on the sealed hierarchy to apply the correct
 * runtime behaviour.
 *
 * <p>Sealed permits list is exhaustive: the dispatcher can switch without a default branch.
 */
public sealed interface WorkflowDirective permits WorkflowDirective.Stop {

  /**
   * Terminates the active execution immediately.
   *
   * @param reason human-readable explanation, included in logs
   */
  record Stop(String reason) implements WorkflowDirective {}
}
