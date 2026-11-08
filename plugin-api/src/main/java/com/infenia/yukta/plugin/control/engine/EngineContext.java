// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Flux;

/** All an engine can reach. It has no handle to the dispatcher, the gateway or the stores. */
public interface EngineContext {

  /**
   * The supervisor this engine decides for.
   *
   * @return its id
   */
  String self();

  /**
   * A timer, for an engine whose settings ask for a period.
   *
   * @param period the period
   * @return one element per period
   */
  Flux<Instant> ticks(Duration period);

  /**
   * A per-supervisor store that survives resubscribing. In memory, or PostgreSQL when configured.
   *
   * @return the mutable state
   */
  Map<String, Object> state();
}
