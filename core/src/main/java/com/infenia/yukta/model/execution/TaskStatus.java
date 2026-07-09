// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.execution;

/** Status enumeration for task execution states. */
public enum TaskStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILURE,
  ERROR;

  /**
   * Check if this status is a terminal state.
   *
   * @return true if status is SUCCESS, FAILURE, or ERROR; false otherwise
   */
  public boolean isTerminal() {
    return this == SUCCESS || this == FAILURE || this == ERROR;
  }
}
