// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

import java.util.Arrays;

/** Status enumeration for workflow and node execution states. */
public enum TaskStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILURE,
  ERROR,
  STOPPED,
  /** The node was cancelled before it finished (a stop, a timeout or a failing sibling). */
  CANCELLED,
  /** The node's idle timeout expired. */
  TIMED_OUT,
  /** The node never ran because the run finished without giving it input. */
  SKIPPED,
  /** The node never ran because the run failed before it got input. */
  UPSTREAM_FAILED;

  /**
   * Check if this status is a terminal state.
   *
   * @return true for every status except PENDING and RUNNING
   */
  public boolean isTerminal() {
    return this != PENDING && this != RUNNING;
  }

  /**
   * Check whether a status given by name is terminal.
   *
   * @param name a status name, possibly null or not a {@code TaskStatus}
   * @return true if it names a terminal status; false for non-terminal or unknown names
   */
  public static boolean isTerminalName(final String name) {
    return Arrays.stream(values()).anyMatch(s -> s.name().equals(name) && s.isTerminal());
  }
}
