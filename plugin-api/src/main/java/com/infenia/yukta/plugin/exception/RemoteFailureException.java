// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import java.io.Serial;
import java.util.Set;

/**
 * A failure that happened on a worker. The original exception cannot cross the wire, so only the
 * names of its class, its superclasses and its causes' classes are kept, and retry lists ({@code
 * retryOn} / {@code noRetryOn}) match against those names.
 */
public class RemoteFailureException extends WorkflowExecutionException {

  @Serial private static final long serialVersionUID = 1L;

  /** Fully qualified class names of the original failure and its causes. */
  private final Set<String> types;

  /**
   * Creates the exception.
   *
   * @param message what went wrong, as reported by the worker
   * @param remoteTypes class names of the original failure, its superclasses and its causes
   */
  public RemoteFailureException(final String message, final Set<String> remoteTypes) {
    super(message);
    this.types = Set.copyOf(remoteTypes);
  }

  /**
   * The class names of the original failure.
   *
   * @return fully qualified names, nested classes with {@code $}
   */
  public Set<String> remoteTypes() {
    return types;
  }
}
