// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** The {@link DecisionModel} plugins use: sends each question to the backend a node chose. */
@Primary
@Component
@RequiredArgsConstructor
public final class DecisionModels implements DecisionModel {

  /** The System One backend. */
  private final SystemOneModel systemOne;

  /** The chat model backend. */
  private final ChatDecisionModel chat;

  @Override
  public Mono<Map<String, Answer>> answer(
      final ModelSpec spec, final String state, final Map<String, Question> questions) {
    return switch (spec.backend()) {
      case SYSTEMONE -> systemOne.answer(spec, state, questions);
      case LLM -> chat.answer(spec, state, questions);
    };
  }
}
