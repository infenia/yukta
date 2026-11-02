// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.NotLeaderException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * What an answer or an escalation must pass before the broker takes it: this server leads, the
 * request exists and belongs to the run the command names, and the sender is who the authoriser
 * lets through. The result is the request and the sender, as a {@link Responder}.
 */
@Component
public class InputCommandGuard {

  /** Tells whether this server leads. */
  private final LeaderElection leader;

  /** Reads the request. */
  private final InputBroker broker;

  /** Works out who sends the command. */
  private final ActorResolver actors;

  /** Decides whether the sender may go on. */
  private final AnswerAuthorizer authorizer;

  /**
   * Creates the guard.
   *
   * @param leader tells whether this server leads
   * @param broker reads the request
   * @param actors works out who sends the command
   * @param authorizer decides whether the sender may go on
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public InputCommandGuard(
      final LeaderElection leader,
      final InputBroker broker,
      final ActorResolver actors,
      final AnswerAuthorizer authorizer) {
    this.leader = leader;
    this.broker = broker;
    this.actors = actors;
    this.authorizer = authorizer;
  }

  /**
   * Checks a command that addresses a request.
   *
   * @param executionId the run the command names
   * @param requestId the request it addresses
   * @param escalation true for handing the request up, false for answering it
   * @return the request and the sender; errors with {@link NotLeaderException} on a standby, {@link
   *     NotFoundException} for an unknown request, {@link ConflictException} for a request of
   *     another run, or {@link InputForbiddenException} when the sender may not
   */
  public Mono<Cleared> clear(
      final String executionId, final String requestId, final boolean escalation) {
    return Mono.defer(
        () -> {
          if (!leader.isLeader()) {
            return Mono.error(new NotLeaderException());
          }
          return broker
              .find(requestId)
              .switchIfEmpty(
                  Mono.error(() -> new NotFoundException("Input request not found: " + requestId)))
              .flatMap(request -> clearFor(request, executionId, escalation));
        });
  }

  private Mono<Cleared> clearFor(
      final InputRequest request, final String executionId, final boolean escalation) {
    if (!request.executionId().equals(executionId)) {
      return Mono.error(
          new ConflictException(
              "Input request "
                  + request.id()
                  + " belongs to another execution than "
                  + executionId));
    }
    return actors
        .resolve()
        .flatMap(
            actor -> {
              if (actor.type() == ActorInfo.Type.UNAUTHENTICATED) {
                return Mono.error(
                    new InputForbiddenException(
                        request.id(), "authentication", "The sender is not identified"));
              }
              return authorizer
                  .authorize(new AnswerContext(request, actor, escalation))
                  .thenReturn(new Cleared(request, responder(actor)));
            });
  }

  private static Responder responder(final ActorInfo actor) {
    final Responder.Type type =
        switch (actor.type()) {
          case SUPERVISOR -> Responder.Type.SUPERVISOR;
          case EXTERNAL -> Responder.Type.EXTERNAL;
          case HUMAN, UNAUTHENTICATED -> Responder.Type.HUMAN;
        };
    return new Responder(type, actor.id());
  }

  /** The request as it is, and who sends the command. */
  public record Cleared(InputRequest request, Responder responder) {}
}
