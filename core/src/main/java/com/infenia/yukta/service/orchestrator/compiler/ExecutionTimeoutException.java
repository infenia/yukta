// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import java.util.concurrent.TimeoutException;

/** The whole run outlived the session's execution timeout, as opposed to one node timing out. */
public class ExecutionTimeoutException extends TimeoutException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param seconds the execution timeout that was exceeded
   */
  public ExecutionTimeoutException(final long seconds) {
    super("Execution exceeded the session timeout of " + seconds + "s");
  }
}
