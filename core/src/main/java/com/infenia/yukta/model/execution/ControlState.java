// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

/**
 * Operator-driven control state of an execution (pause, step, skip, ...).
 *
 * <p>Distinct from {@link TaskStatus}, which is the lifecycle status of a workflow or node. Control
 * state never terminates an execution.
 */
public enum ControlState {
  PAUSED,
  RESUMED,
  NODE_PAUSED,
  NODE_RESUMED,
  NODE_STEPPED,
  NODE_STOPPED,
  NODE_SKIPPED,
  NODE_UNSKIPPED,
  STEP_MODE_ENABLED,
  STEP_MODE_DISABLED,
  STOPPING
}
