// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

/**
 * An error that reached a node on its input: the node did not fail, a node before it did.
 *
 * <p>It lets the tracker show only the node that actually failed as {@code FAILURE}, and the nodes
 * behind it as {@code UPSTREAM_FAILED}.
 */
public final class UpstreamFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private UpstreamFailure(final Throwable cause) {
    super(cause.getMessage(), cause);
  }

  /**
   * Marks an error seen on a node's input; an error that is already marked is kept as it is.
   *
   * @param error the error from upstream
   * @return the error marked as coming from upstream
   */
  public static Throwable mark(final Throwable error) {
    return error instanceof UpstreamFailure ? error : new UpstreamFailure(error);
  }
}
