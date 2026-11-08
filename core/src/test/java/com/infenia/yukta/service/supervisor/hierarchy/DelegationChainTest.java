// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.Delegation;
import com.infenia.yukta.plugin.control.DelegationMode;
import com.infenia.yukta.service.supervisor.hierarchy.DelegationChain.Outcome;
import com.infenia.yukta.service.supervisor.hierarchy.DelegationChain.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Each mode with allowing, refusing, silent and slow parents; a refusal is final in all of them.
 */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class DelegationChainTest {

  private static final Duration SHORT = Duration.ofMillis(100);

  private static Delegation mode(final DelegationMode mode) {
    return new Delegation(mode, SHORT);
  }

  private static Function<String, Mono<Reply>> parents(final Map<String, Mono<Reply>> by) {
    return id -> by.getOrDefault(id, Mono.never());
  }

  private static Mono<Reply> allow() {
    return Mono.just(Reply.allow());
  }

  private static Mono<Reply> deny() {
    return Mono.just(Reply.deny("closed"));
  }

  private static Mono<Reply> silent() {
    return Mono.just(Reply.noOpinion());
  }

  @Test
  void orderedFallsThroughSilentAndSlowParentToTheFirstAnswer() {
    final var ask = parents(Map.of("a", silent(), "b", Mono.never(), "c", allow(), "d", deny()));

    StepVerifier.create(
            DelegationChain.resolve(mode(DelegationMode.ORDERED), List.of("a", "b", "c", "d"), ask))
        .assertNext(
            o -> {
              assertThat(o.kind()).isEqualTo(Outcome.Kind.ALLOWED);
              assertThat(o.by()).isEqualTo("c");
              assertThat(o.asked()).containsExactly("a:NO_OPINION", "b:silent", "c:ALLOW");
            })
        .verifyComplete();
  }

  @Test
  void orderedRefusalIsFinalEvenWhenLaterParentWouldAllow() {
    final var ask = parents(Map.of("a", deny(), "b", allow()));

    StepVerifier.create(
            DelegationChain.resolve(mode(DelegationMode.ORDERED), List.of("a", "b"), ask))
        .assertNext(
            o -> {
              assertThat(o.kind()).isEqualTo(Outcome.Kind.REFUSED);
              assertThat(o.by()).isEqualTo("a");
              assertThat(o.reason()).isEqualTo("closed");
              assertThat(o.asked()).containsExactly("a:DENY");
            })
        .verifyComplete();
  }

  @Test
  void allNeedsEveryParentToAllow() {
    StepVerifier.create(
            DelegationChain.resolve(
                mode(DelegationMode.ALL),
                List.of("a", "b"),
                parents(Map.of("a", allow(), "b", allow()))))
        .assertNext(o -> assertThat(o.kind()).isEqualTo(Outcome.Kind.ALLOWED))
        .verifyComplete();
  }

  @Test
  void allWithOneSilentOrSlowParentGoesToPerson() {
    StepVerifier.create(
            DelegationChain.resolve(
                mode(DelegationMode.ALL),
                List.of("a", "b", "c"),
                parents(Map.of("a", allow(), "b", silent(), "c", Mono.never()))))
        .assertNext(
            o -> {
              assertThat(o.kind()).isEqualTo(Outcome.Kind.PERSON);
              assertThat(o.asked())
                  .containsExactlyInAnyOrder("a:ALLOW", "b:NO_OPINION", "c:silent");
            })
        .verifyComplete();
  }

  @Test
  void allRefusalByAnyParentIsFinal() {
    StepVerifier.create(
            DelegationChain.resolve(
                mode(DelegationMode.ALL),
                List.of("a", "b"),
                parents(Map.of("a", allow(), "b", deny()))))
        .assertNext(
            o -> {
              assertThat(o.kind()).isEqualTo(Outcome.Kind.REFUSED);
              assertThat(o.by()).isEqualTo("b");
            })
        .verifyComplete();
  }

  @Test
  void anyTakesTheFirstAnswerAndIgnoresTheSilent() {
    StepVerifier.create(
            DelegationChain.resolve(
                mode(DelegationMode.ANY),
                List.of("a", "b"),
                parents(Map.of("a", Mono.never(), "b", allow()))))
        .assertNext(
            o -> {
              assertThat(o.kind()).isEqualTo(Outcome.Kind.ALLOWED);
              assertThat(o.by()).isEqualTo("b");
            })
        .verifyComplete();
  }

  @Test
  void anyRefusalThatComesFirstIsFinal() {
    StepVerifier.create(
            DelegationChain.resolve(
                mode(DelegationMode.ANY),
                List.of("a", "b"),
                id -> "a".equals(id) ? deny() : allow().delayElement(Duration.ofMillis(50))))
        .assertNext(o -> assertThat(o.kind()).isEqualTo(Outcome.Kind.REFUSED))
        .verifyComplete();
  }

  @Test
  void noParentsAllSilentOrFailingParentsGoToPersonWithTheChainRecorded() {
    for (final DelegationMode m : DelegationMode.values()) {
      StepVerifier.create(DelegationChain.resolve(mode(m), List.of(), parents(Map.of())))
          .assertNext(
              o -> {
                assertThat(o.kind()).isEqualTo(Outcome.Kind.PERSON);
                assertThat(o.asked()).isEmpty();
              })
          .verifyComplete();
      StepVerifier.create(
              DelegationChain.resolve(
                  mode(m),
                  List.of("a", "b"),
                  parents(
                      Map.of("a", Mono.error(new IllegalStateException("down")), "b", silent()))))
          .assertNext(
              o -> {
                assertThat(o.kind()).isEqualTo(Outcome.Kind.PERSON);
                assertThat(o.asked()).hasSize(2);
              })
          .verifyComplete();
    }
  }

  @Test
  void delegationNeedsPositiveTimeout() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new Delegation(DelegationMode.ANY, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Delegation.DEFAULT.mode()).isEqualTo(DelegationMode.ORDERED);
  }
}
