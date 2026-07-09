// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

/** Failure strategies for loop wrappers. */
public enum FailureStrategy {
  /** Abort the workflow execution. */
  ABORT,
  /** Retry the current iteration. */
  RETRY,
  /** Skip the current iteration and move to the next. */
  SKIP,
  /** Escalate the error to the global handler (stops parent workflow). */
  ESCALATE
}
