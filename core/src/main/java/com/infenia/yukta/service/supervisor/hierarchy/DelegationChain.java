// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import com.infenia.yukta.plugin.control.Delegation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Asks the parents of a supervisor to decide something it delegates, in the mode set for that kind
 * of delegation. Whatever the mode, a refusal from a parent that was asked is final, and when no
 * parent decides (none configured, all silent, the chain used up) the request goes to a person with
 * the chain it passed through recorded.
 */
public final class DelegationChain {

  private DelegationChain() {}

  /** What one parent said. */
  public enum Answer {
    /** Allow it. */
    ALLOW,
    /** Refuse it. */
    DENY,
    /** No opinion. */
    NO_OPINION
  }

  /**
   * How the parents ended up.
   *
   * @param kind who decided
   * @param by the parent that allowed or refused, or {@code null} when a person decides
   * @param reason the refusal reason, or {@code null}
   * @param asked the parents asked, with what each said ({@code silent} for no answer, a timeout or
   *     an error), in the order they were asked
   */
  public record Outcome(
      Kind kind, @Nullable String by, @Nullable String reason, List<String> asked) {

    /** Who decided. */
    public enum Kind {
      /** A parent allowed it. */
      ALLOWED,
      /** A parent refused it; final. */
      REFUSED,
      /** No parent decided: a person does. */
      PERSON
    }
  }

  /** One parent's answer, or none when it was silent. */
  private record Asked(String parent, @Nullable Answer answer, @Nullable String reason) {}

  /**
   * Asks the parents.
   *
   * @param delegation the mode and the time each parent has
   * @param parents the parent ids, in order
   * @param ask asks one parent; an error or no answer within the time counts as silent
   * @return the outcome
   */
  public static Mono<Outcome> resolve(
      final Delegation delegation,
      final List<String> parents,
      final Function<String, Mono<Reply>> ask) {
    return switch (delegation.mode()) {
      case ORDERED -> ordered(delegation, parents, ask);
      case ALL -> all(delegation, parents, ask);
      case ANY -> any(delegation, parents, ask);
    };
  }

  /**
   * What a parent says when asked.
   *
   * @param answer what it said
   * @param reason why, for a refusal
   */
  public record Reply(Answer answer, @Nullable String reason) {

    /**
     * An allow.
     *
     * @return the reply
     */
    public static Reply allow() {
      return new Reply(Answer.ALLOW, null);
    }

    /**
     * A refusal.
     *
     * @param reason why
     * @return the reply
     */
    public static Reply deny(final String reason) {
      return new Reply(Answer.DENY, reason);
    }

    /**
     * No opinion.
     *
     * @return the reply
     */
    public static Reply noOpinion() {
      return new Reply(Answer.NO_OPINION, null);
    }
  }

  private static Mono<Asked> askOne(
      final Delegation delegation, final String parent, final Function<String, Mono<Reply>> ask) {
    return Mono.defer(() -> ask.apply(parent))
        .timeout(delegation.timeout())
        .map(reply -> new Asked(parent, reply.answer(), reply.reason()))
        .onErrorResume(_ -> Mono.empty())
        .defaultIfEmpty(new Asked(parent, null, null));
  }

  private static String line(final Asked reply) {
    return reply.parent() + ":" + (reply.answer() == null ? "silent" : reply.answer().name());
  }

  private static Mono<Outcome> ordered(
      final Delegation delegation,
      final List<String> parents,
      final Function<String, Mono<Reply>> ask) {
    final List<String> asked = new ArrayList<>();
    return Flux.fromIterable(parents)
        .concatMap(parent -> askOne(delegation, parent, ask))
        .doOnNext(reply -> asked.add(line(reply)))
        .filter(reply -> reply.answer() == Answer.ALLOW || reply.answer() == Answer.DENY)
        .next()
        .map(reply -> decided(reply, asked))
        .switchIfEmpty(Mono.fromSupplier(() -> person(asked)));
  }

  private static Mono<Outcome> all(
      final Delegation delegation,
      final List<String> parents,
      final Function<String, Mono<Reply>> ask) {
    return Flux.fromIterable(parents)
        .flatMap(parent -> askOne(delegation, parent, ask))
        .collectList()
        .map(
            replies -> {
              final List<String> asked = replies.stream().map(DelegationChain::line).toList();
              return replies.stream()
                  .filter(r -> r.answer() == Answer.DENY)
                  .findFirst()
                  .map(r -> decided(r, asked))
                  .orElseGet(
                      () ->
                          !replies.isEmpty()
                                  && replies.stream().allMatch(r -> r.answer() == Answer.ALLOW)
                              ? new Outcome(Outcome.Kind.ALLOWED, null, null, asked)
                              : person(asked));
            });
  }

  private static Mono<Outcome> any(
      final Delegation delegation,
      final List<String> parents,
      final Function<String, Mono<Reply>> ask) {
    final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
    return Flux.fromIterable(parents)
        .flatMap(parent -> askOne(delegation, parent, ask))
        .doOnNext(reply -> asked.add(line(reply)))
        .filter(reply -> reply.answer() == Answer.ALLOW || reply.answer() == Answer.DENY)
        .next()
        .map(reply -> decided(reply, List.copyOf(asked)))
        .switchIfEmpty(Mono.fromSupplier(() -> person(List.copyOf(asked))));
  }

  private static Outcome decided(final Asked reply, final List<String> asked) {
    return reply.answer() == Answer.ALLOW
        ? new Outcome(Outcome.Kind.ALLOWED, reply.parent(), null, List.copyOf(asked))
        : new Outcome(
            Outcome.Kind.REFUSED,
            reply.parent(),
            reply.reason() == null ? "refused" : reply.reason(),
            List.copyOf(asked));
  }

  private static Outcome person(final List<String> asked) {
    return new Outcome(Outcome.Kind.PERSON, null, null, List.copyOf(asked));
  }
}
