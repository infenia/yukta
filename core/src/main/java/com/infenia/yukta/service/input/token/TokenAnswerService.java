// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.token;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.route.ResponderType;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Turns a one-time token into an ordinary answer. The caller has no API key: the token stands for
 * the tier the request was delivered to. The answer is sent as {@code token:<tier>} through the
 * control dispatcher, so review, audit and the authoriser apply as for any answer.
 *
 * <p>Every way of being refused before the answer is sent is the same {@link
 * InvalidAnswerTokenException}.
 *
 * @since 0.1.0-alpha.4
 */
@Service
public class TokenAnswerService {

  /** Counter of token answers by outcome. */
  private static final String METRIC = "yukta.input.token";

  /** The prefix of the actor of a token answer. */
  private static final String ACTOR_PREFIX = "token:";

  /** Finds requests. */
  private final InputBroker broker;

  /** Makes and checks tokens. */
  private final AnswerTokens tokens;

  /** Finds the route, to know the type of a tier. */
  private final RouteResolver routes;

  /** Sends the answer. */
  private final ControlBusGateway controlBus;

  /** Registry for the metrics. */
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param broker finds requests
   * @param tokens makes and checks tokens
   * @param routes finds the route of a request
   * @param controlBus sends the answer
   * @param meters registry for the metrics
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public TokenAnswerService(
      final InputBroker broker,
      final AnswerTokens tokens,
      final RouteResolver routes,
      final ControlBusGateway controlBus,
      final MeterRegistry meters) {
    this.broker = broker;
    this.tokens = tokens;
    this.routes = routes;
    this.controlBus = controlBus;
    this.meters = meters;
  }

  /**
   * The answer a token carries.
   *
   * @param requestId the request
   * @param tier the tier the token was made for
   * @param expires the expiry second of the token
   * @param token the token
   * @param decision the decision
   * @param text free text, or {@code null}
   * @param data structured data, or {@code null}
   * @param rationale why, or {@code null}
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  public record Answer(
      String requestId,
      String tier,
      long expires,
      String token,
      String decision,
      @Nullable String text,
      @Nullable Map<String, Object> data,
      @Nullable String rationale) {

    /** Keeps a copy of the data. */
    public Answer {
      data =
          data == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }

  /**
   * Answers with a token.
   *
   * @param answer the answer
   * @return completes when the answer is taken; errors with {@link InvalidAnswerTokenException}
   *     when the token does not allow it, or with the usual answer errors otherwise
   */
  public Mono<Void> answer(final Answer answer) {
    if (!tokens.verify(answer.requestId(), answer.tier(), answer.expires(), answer.token())) {
      return refused("invalid");
    }
    return broker
        .find(answer.requestId())
        .filter(request -> request.tier().equals(answer.tier()) && !request.state().isTerminal())
        // send completes empty on success, so only a missing request is refused here
        .flatMap(request -> send(answer, request).thenReturn(Boolean.TRUE))
        .switchIfEmpty(Mono.defer(() -> refused("invalid").thenReturn(Boolean.FALSE)))
        .then();
  }

  private Mono<Void> send(final Answer answer, final InputRequest request) {
    return routes
        .resolve(request.spec(), request.sessionId())
        .flatMap(
            route -> {
              final Tier tier =
                  route.tiers().stream()
                      .filter(t -> t.name().equals(request.tier()))
                      .findFirst()
                      .orElse(null);
              final boolean external =
                  request.targets().stream().anyMatch(t -> t.startsWith("external:"));
              final boolean mayHandle =
                  tier != null
                      && external
                      && (request.spec().risk() != InputRisk.HIGH
                          || tier.type() == ResponderType.HUMAN);
              if (!mayHandle
                  || !tokens.claim(answer.requestId(), answer.tier(), answer.expires())) {
                return refused(mayHandle ? "replay" : "refused");
              }
              return controlBus
                  .answerInput(
                      request.executionId(),
                      request.id(),
                      null,
                      answer.decision(),
                      answer.text(),
                      answer.data(),
                      answer.rationale(),
                      null)
                  .contextWrite(
                      ctx ->
                          ctx.put(ControlActor.CONTEXT_KEY, ACTOR_PREFIX + answer.tier())
                              .put(ControlActor.KIND_KEY, ControlActor.Kind.TOKEN))
                  .doOnSuccess(_ -> meters.counter(METRIC, "outcome", "accepted").increment())
                  .doOnError(
                      _ -> {
                        tokens.release(answer.requestId(), answer.tier());
                        meters.counter(METRIC, "outcome", "failed").increment();
                      });
            });
  }

  private Mono<Void> refused(final String outcome) {
    meters.counter(METRIC, "outcome", outcome).increment();
    return Mono.error(new InvalidAnswerTokenException());
  }
}
