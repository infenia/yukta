// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Tests for DecisionModels. */
@NoArgsConstructor
class DecisionModelsTest {

  private static ModelSpec spec(final ModelSpec.Backend backend) {
    return new ModelSpec(backend, "openai", "m", null, null, Duration.ofSeconds(1));
  }

  @Test
  void answer_routesByBackend() {
    final SystemOneModel systemOne = mock(SystemOneModel.class);
    final ChatDecisionModel chat = mock(ChatDecisionModel.class);
    final Map<String, Answer> fromSystemOne = Map.of("q", new Answer.YesNo(1));
    final Map<String, Answer> fromChat = Map.of("q", new Answer.YesNo(0));
    when(systemOne.answer(spec(ModelSpec.Backend.SYSTEMONE), "s", Map.of()))
        .thenReturn(Mono.just(fromSystemOne));
    when(chat.answer(spec(ModelSpec.Backend.LLM), "s", Map.of())).thenReturn(Mono.just(fromChat));
    final DecisionModels models = new DecisionModels(systemOne, chat);

    assertThat(models.answer(spec(ModelSpec.Backend.SYSTEMONE), "s", Map.of()).block())
        .isSameAs(fromSystemOne);
    assertThat(models.answer(spec(ModelSpec.Backend.LLM), "s", Map.of()).block())
        .isSameAs(fromChat);
  }
}
