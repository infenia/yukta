// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import java.io.Serial;

/**
 * A plugin's order to end the whole run, for example because a hard budget is used up. Unlike an
 * ordinary failure it is never retried, dropped ({@code onError} CONTINUE) or routed to a dead
 * letter, and a plugin's own {@code failureMode} does not absorb it.
 */
public class RunAbortedException extends WorkflowExecutionException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the run must end
   */
  public RunAbortedException(final String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message why the run must end
   * @param cause the failure that made it end
   */
  public RunAbortedException(final String message, final Throwable cause) {
    super(message, cause);
  }

  /**
   * Whether an error, or something it was caused by, is a {@code RunAbortedException}.
   *
   * @param error the error
   * @return true if the run must end
   */
  public static boolean isAbort(final Throwable error) {
    boolean abort = false;
    Throwable cause = error;
    // bounded, so a cause chain that loops back on itself cannot spin forever
    for (int depth = 0; !abort && cause != null && depth < 8; depth++) {
      abort = cause instanceof RunAbortedException;
      cause = cause.getCause();
    }
    return abort;
  }
}
