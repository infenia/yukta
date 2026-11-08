// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import reactor.core.publisher.Flux;

/**
 * The decision maker of one supervisor. Signals go in, directives come out. An engine has no handle
 * to the dispatcher, the stores or any core bean: the supervisor checks every directive.
 */
@FunctionalInterface
public interface SupervisorEngine extends AutoCloseable {

  /**
   * Decides.
   *
   * @param signals what the supervisor owns and what it asks
   * @return the directives; a failure resubscribes with backoff and never touches a run
   */
  Flux<Directive> run(Flux<Signal> signals);

  /** Releases what the engine holds; called when the supervisor is detached. */
  @Override
  default void close() {
    // nothing to release by default
  }
}
