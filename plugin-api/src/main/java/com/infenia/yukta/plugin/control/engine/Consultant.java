// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.DelegationKind;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Asks the parents of a supervisor a question for its engine. The server gives one to every
 * supervisor that is {@link ConsultAware}.
 */
@FunctionalInterface
public interface Consultant {

  /**
   * Asks the parents, in the delegation mode and within the timeout set for the kind.
   *
   * @param kind what is delegated
   * @param payload the question
   * @return the typed answer of each parent that gave one, by parent supervisor id; empty when none
   *     did
   */
  Mono<Map<String, Map<String, Object>>> consult(DelegationKind kind, Map<String, Object> payload);
}
