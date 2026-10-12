// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.util.VariableResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/** A decision model for plugin tests: answers from a function and records what it was asked. */
@SuppressWarnings("PMD.CommentRequired")
final class FakeModel implements DecisionModel {

  /** What the model was asked. */
  /* default */ final List<Asked> asked = new ArrayList<>();

  private final Function<Asked, Mono<Map<String, Answer>>> reply;

  /* default */ FakeModel(final Function<Asked, Mono<Map<String, Answer>>> reply) {
    this.reply = reply;
  }

  /** A model that always gives the same answers. */
  /* default */ static FakeModel always(final Map<String, Answer> answers) {
    return new FakeModel(_ -> Mono.just(answers));
  }

  /** One request to the model. */
  /* default */ record Asked(ModelSpec spec, String state, Map<String, Question> questions) {}

  @Override
  public Mono<Map<String, Answer>> answer(
      final ModelSpec spec, final String state, final Map<String, Question> questions) {
    final Asked request = new Asked(spec, state, questions);
    asked.add(request);
    return reply.apply(request);
  }

  /** A resolver that returns every value as it is. */
  /* default */ static VariableResolver plainResolver() {
    final VariableResolver resolver = Mockito.mock(VariableResolver.class);
    Mockito.lenient()
        .when(resolver.resolve(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    return resolver;
  }

  /** A message with a payload. */
  /* default */ static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }
}
