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
package com.infenia.yukta.model.control;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * An inbound control command directed at an active workflow execution.
 *
 * <p>Unlike observational signals (heartbeat, stats), a {@code ControlCommand} requests a
 * behavioral change: stop the workflow, restart it, or replay from a specific node. Commands are
 * emitted onto the Control Bus and routed through registered {@code ControlSignalProcessor}
 * implementations before producing a {@code WorkflowDirective}.
 *
 * @param type the kind of action being requested
 * @param workflowId the workflow to target
 * @param sessionId the session that owns the workflow
 * @param targetNodeId optional node identifier; required for {@link CommandType#RESTART_FROM_NODE}
 * @param params arbitrary extra parameters passed to processors
 */
public record ControlCommand(
    CommandType type,
    String workflowId,
    String sessionId,
    @Nullable String targetNodeId,
    Map<String, Object> params) {

  /** Compact constructor: makes params immutable. */
  public ControlCommand {
    params = Map.copyOf(params);
  }

  /** Convenience constructor with no extra parameters. */
  public ControlCommand(final CommandType type, final String workflowId, final String sessionId) {
    this(type, workflowId, sessionId, null, Map.of());
  }

  /** Convenience constructor for node-targeted commands. */
  public ControlCommand(
      final CommandType type,
      final String workflowId,
      final String sessionId,
      final String targetNodeId) {
    this(type, workflowId, sessionId, targetNodeId, Map.of());
  }

  /** The set of actions that can be requested via a {@link ControlCommand}. */
  public enum CommandType {
    /** Terminates the active workflow execution immediately. */
    STOP,
    /** Stops the current execution and re-runs it from the beginning with the original payload. */
    RESTART,
    /**
     * Stops the current execution and re-runs from {@code targetNodeId}, replaying the last
     * checkpoint message produced by each of that node's direct parents.
     */
    RESTART_FROM_NODE
  }
}
