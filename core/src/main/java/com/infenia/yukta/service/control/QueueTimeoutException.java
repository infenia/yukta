// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

/** A queued start waited longer than {@code yukta.execution.queue-timeout} for a slot. */
public class QueueTimeoutException extends ExecutionRejectedException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the start was given up
   */
  public QueueTimeoutException(final String message) {
    super(message);
  }
}
