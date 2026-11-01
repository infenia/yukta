// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * The rules every answer and every escalation must pass, in this order: {@code audience}, {@code
 * tier}, {@code risk} (answers only), {@code scope} and {@code separation}. Each failing rule has
 * its own message and counts in {@code yukta.input.denied{rule}}.
 *
 * <p>A request of risk {@code HIGH} can only be answered by a human; there is no setting that
 * changes this. A supervisor answers only when its profile says {@code canAnswer} and its {@code
 * maxRisk} reaches the request's risk.
 */
public class DefaultAnswerAuthorizer implements AnswerAuthorizer {

  /** Who the implicit route of a request names until routes exist. */
  public static final List<String> DEFAULT_TARGETS = List.of("supervisor:*", "role:OPERATOR");

  /** Start payload key under which a run names the supervisor that started it (written later). */
  public static final String STARTER_KEY = "yukta.supervisor.id";

  /** Counter of denied answers, shared with the broker's caps. */
  private static final String DENIED_METRIC = "yukta.input.denied";

  private static final String RULE_AUDIENCE = "audience";
  private static final String RULE_TIER = "tier";
  private static final String RULE_RISK = "risk";
  private static final String RULE_SCOPE = "scope";
  private static final String RULE_SEPARATION = "separation";

  /** Finds the start payload of a run. */
  private final ExecutionControlStore runs;

  /** Counts denials. */
  private final MeterRegistry meters;

  /**
   * Creates the authoriser.
   *
   * @param runs finds the start payload of a run
   * @param meters counts denials
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public DefaultAnswerAuthorizer(final ExecutionControlStore runs, final MeterRegistry meters) {
    this.runs = runs;
    this.meters = meters;
  }

  @Override
  public Mono<Void> authorize(final AnswerContext context) {
    return Mono.fromRunnable(() -> check(context));
  }

  private void check(final AnswerContext context) {
    final InputRequest request = context.request();
    final ActorInfo actor = context.actor();
    final List<String> tierTargets = tierTargets(request);
    final List<String> audience =
        request.spec().audience().isCurrentTier()
            ? tierTargets
            : request.spec().audience().selectors();
    if (!matchesAny(actor, audience)) {
      throw deny(request, RULE_AUDIENCE, actor, "is not in the audience of the request");
    }
    if (!matchesAny(actor, tierTargets)) {
      throw deny(request, RULE_TIER, actor, "is not a target of tier '" + request.tier() + "'");
    }
    if (!context.escalation()) {
      checkRisk(request, actor);
    }
    checkScope(request, actor);
    checkSeparation(request, actor);
  }

  private static List<String> tierTargets(final InputRequest request) {
    return request.targets().isEmpty() ? DEFAULT_TARGETS : request.targets();
  }

  private void checkRisk(final InputRequest request, final ActorInfo actor) {
    final InputRisk risk = request.spec().risk();
    if (risk == InputRisk.HIGH && actor.type() != ActorInfo.Type.HUMAN) {
      throw deny(request, RULE_RISK, actor, "may not answer a request of risk HIGH; only a human");
    }
    if (actor.type() == ActorInfo.Type.SUPERVISOR) {
      checkSupervisorRisk(request, actor, risk);
    }
  }

  private void checkSupervisorRisk(
      final InputRequest request, final ActorInfo actor, final InputRisk risk) {
    final SupervisorCapabilities capabilities = actor.capabilities();
    if (capabilities == null || !capabilities.canAnswer()) {
      throw deny(request, RULE_RISK, actor, "may not answer: canAnswer is off");
    }
    if (!capabilities.mayAnswer(risk)) {
      throw deny(
          request,
          RULE_RISK,
          actor,
          "may answer up to risk " + capabilities.maxRisk() + ", the request is " + risk);
    }
  }

  private void checkScope(final InputRequest request, final ActorInfo actor) {
    if (actor.type() == ActorInfo.Type.SUPERVISOR) {
      checkSupervisorScope(request, actor);
    } else if (actor.type() == ActorInfo.Type.HUMAN) {
      checkKeySessions(request, actor);
    }
  }

  private void checkSupervisorScope(final InputRequest request, final ActorInfo actor) {
    if (actor.scope() == null
        || !actor.scope().matches(request.sessionId(), request.workflowId())) {
      throw deny(request, RULE_SCOPE, actor, "does not watch the run of the request");
    }
  }

  private void checkKeySessions(final InputRequest request, final ActorInfo actor) {
    final Set<String> sessions = Objects.requireNonNullElse(actor.sessions(), Set.<String>of());
    if (!sessions.isEmpty() && !sessions.contains(request.sessionId())) {
      throw deny(request, RULE_SCOPE, actor, "is limited to other sessions than the request's");
    }
  }

  private void checkSeparation(final InputRequest request, final ActorInfo actor) {
    if (actor.type() == ActorInfo.Type.SUPERVISOR
        && runs.findByExecutionId(request.executionId())
            .map(control -> control.payload().get(STARTER_KEY))
            .filter(starter -> Objects.equals(starter, actor.id()))
            .isPresent()) {
      throw deny(request, RULE_SEPARATION, actor, "started the run, so it may not answer for it");
    }
  }

  private static boolean matchesAny(final ActorInfo actor, final List<String> selectors) {
    return selectors.stream().anyMatch(selector -> matches(actor, selector));
  }

  private static boolean matches(final ActorInfo actor, final String selector) {
    return switch (actor.type()) {
      case HUMAN ->
          ("key:" + actor.id()).equals(selector)
              || ("role:OPERATOR".equals(selector)
                  && (actor.role() == ApiRole.OPERATOR || actor.role() == ApiRole.ADMIN))
              || ("role:ADMIN".equals(selector) && actor.role() == ApiRole.ADMIN);
      case SUPERVISOR ->
          "supervisor:*".equals(selector) || ("supervisor:" + actor.id()).equals(selector);
      case EXTERNAL -> ("external:" + actor.id()).equals(selector);
      case UNAUTHENTICATED -> false;
    };
  }

  private InputForbiddenException deny(
      final InputRequest request, final String rule, final ActorInfo actor, final String why) {
    meters.counter(DENIED_METRIC, "rule", rule).increment();
    return new InputForbiddenException(
        request.id(),
        rule,
        "Rule '"
            + rule
            + "': "
            + actor.type().name().toLowerCase(Locale.ROOT)
            + " '"
            + actor.id()
            + "' "
            + why);
  }
}
