// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;

/** No worker of the pool had a free slot within the placement's queue timeout. */
public class NoFreeWorkerException extends WorkflowExecutionException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the attempt waited for and how long
   */
  public NoFreeWorkerException(final String message) {
    super(message);
  }
}
