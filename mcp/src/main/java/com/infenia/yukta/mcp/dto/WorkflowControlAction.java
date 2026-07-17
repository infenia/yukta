// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/** Workflow-level control actions available via the {@code control_workflow} MCP tool. */
public enum WorkflowControlAction {
  /** Pause a running execution. */
  PAUSE,
  /** Resume a paused execution. */
  RESUME,
  /** Stop a single execution. */
  STOP,
  /** Stop all active executions of a workflow. */
  STOP_ALL,
  /** Restart an execution from the beginning. */
  RESTART,
  /** Restart an execution from a specific node. */
  RESTART_FROM_NODE
}
