// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** The {@link DecisionModel} plugins use: sends each question to the backend a node chose. */
@Primary
@Component
public final class DecisionModels implements DecisionModel {

  /** The backends, by kind. */
  private final Map<String, DecisionBackend> backends;

  /**
   * Creates the selector.
   *
   * @param backends every decision backend in the context
   */
  public DecisionModels(final List<DecisionBackend> backends) {
    this.backends =
        backends.stream().collect(Collectors.toUnmodifiableMap(DecisionBackend::kind, b -> b));
  }

  @Override
  public Mono<Map<String, Answer>> answer(
      final ModelSpec spec, final String state, final Map<String, Question> questions) {
    final DecisionBackend backend = backends.get(spec.backend().name());
    if (backend == null) {
      return Mono.error(
          new IllegalStateException(
              "No decision backend '" + spec.backend() + "'; available: " + backends.keySet()));
    }
    return backend.answer(spec, state, questions);
  }
}
