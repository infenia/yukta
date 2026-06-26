/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin.control;

/**
 * A typed action to be applied to an active workflow execution.
 *
 * <p>{@code WorkflowDirective} is the output side of the control-bus plugin pipeline. A {@link
 * ControlSignalProcessor} converts an inbound {@link
 * com.infenia.yukta.plugin.message.control.ControlCommand} into one of the concrete directive
 * types. The {@code DirectiveDispatcher} then pattern-matches on the sealed hierarchy to apply the
 * correct runtime behaviour.
 *
 * <p>Sealed permits list is exhaustive: the dispatcher can switch without a default branch.
 */
public sealed interface WorkflowDirective
    permits WorkflowDirective.Stop, WorkflowDirective.Restart, WorkflowDirective.RestartFromNode {

  /**
   * Terminates the active execution immediately.
   *
   * @param reason human-readable explanation, included in logs
   */
  record Stop(String reason) implements WorkflowDirective {}

  /** Stops the current execution and re-runs it from the beginning with the original payload. */
  record Restart() implements WorkflowDirective {}

  /**
   * Stops the current execution and replays from a specific node using the last checkpoint messages
   * produced by that node's direct parents.
   *
   * @param nodeId the node from which execution should resume
   */
  record RestartFromNode(String nodeId) implements WorkflowDirective {}
}
