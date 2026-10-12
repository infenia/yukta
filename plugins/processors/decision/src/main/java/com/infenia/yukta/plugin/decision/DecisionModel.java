// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.Map;
import reactor.core.publisher.Mono;

/** Answers constrained questions about a piece of state. Any model can sit behind it. */
@FunctionalInterface
public interface DecisionModel {

  /**
   * Answers all questions in one call.
   *
   * @param spec which model to ask
   * @param state what the questions are about
   * @param questions the questions, by name
   * @return the answers, by question name; an error if the model cannot answer
   */
  Mono<Map<String, Answer>> answer(ModelSpec spec, String state, Map<String, Question> questions);
}
