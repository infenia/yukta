// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/** Node-level control actions available via the {@code control_node} MCP tool. */
public enum NodeControlAction {
  /** Pause a node. */
  PAUSE,
  /** Resume a paused node. */
  RESUME,
  /** Stop a node. */
  STOP,
  /** Mark a node to be skipped. */
  SKIP,
  /** Clear the skip mark on a node. */
  UNSKIP,
  /** Execute a single step of a node in step mode. */
  STEP,
  /** Enable step-by-step execution mode for a node. */
  STEP_ENABLE,
  /** Disable step-by-step execution mode for a node. */
  STEP_DISABLE
}
