// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import com.infenia.yukta.plugin.control.Delegation;
import com.infenia.yukta.plugin.control.DelegationMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** A question to the parents: the mode decides whose answer counts. */
class ParentConsultingTest {

  private static final List<String> PARENTS = List.of("a", "b", "c");

  private static Delegation how(final DelegationMode mode) {
    return new Delegation(mode, Duration.ofMillis(200));
  }

  private static Function<String, Mono<Map<String, Object>>> answers() {
    return parent ->
        switch (parent) {
          case "a" -> Mono.empty();
          case "b" -> Mono.just(Map.of("from", "b"));
          default -> Mono.just(Map.of("from", "c"));
        };
  }

  @Test
  void orderedTakesTheFirstAnswerAndSkipsTheSilent() {
    StepVerifier.create(ParentConsulting.consult(how(DelegationMode.ORDERED), PARENTS, answers()))
        .expectNext(Map.of("b", Map.of("from", "b")))
        .verifyComplete();
  }

  @Test
  void allHoldsEveryAnswerGiven() {
    StepVerifier.create(ParentConsulting.consult(how(DelegationMode.ALL), PARENTS, answers()))
        .expectNext(Map.of("b", Map.of("from", "b"), "c", Map.of("from", "c")))
        .verifyComplete();
  }

  @Test
  void anyTakesAnAnswerAndNeverWaitsForTheSlowOne() {
    final Function<String, Mono<Map<String, Object>>> ask =
        parent -> "a".equals(parent) ? Mono.never() : Mono.just(Map.of("from", parent));
    StepVerifier.create(ParentConsulting.consult(how(DelegationMode.ANY), PARENTS, ask))
        .assertNext(result -> org.assertj.core.api.Assertions.assertThat(result).hasSize(1))
        .verifyComplete();
  }

  @Test
  void aSlowOrFailingParentIsSilentAndNoAnswerIsEmpty() {
    final Function<String, Mono<Map<String, Object>>> ask =
        parent -> "a".equals(parent) ? Mono.never() : Mono.error(new IllegalStateException("down"));
    for (final DelegationMode mode : DelegationMode.values()) {
      StepVerifier.create(ParentConsulting.consult(how(mode), PARENTS, ask))
          .expectNext(Map.of())
          .verifyComplete();
    }
  }
}
