// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

/** Failure strategies for loop wrappers. */
public enum FailureStrategy {
  /** Abort the workflow execution. */
  ABORT,
  /**
   * Run the iteration again with the same input. The failed attempt still counts as an iteration,
   * so {@code maxIterations} and {@code maxDuration} bound the retries.
   */
  RETRY,
  /**
   * Drop the failed iteration's output and go on with the next one. A failed iteration produces
   * nothing, so the next iteration starts from the same input and, like RETRY, uses up an
   * iteration; the two differ only in what they are called.
   */
  SKIP,
  /** End the whole run with a {@code RunAbortedException}; nothing retries or routes it. */
  ESCALATE
}
