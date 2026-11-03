// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
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
 * tier}, {@code risk} (answers only), {@code scope} and {@code separation}; for an answer to the
 * approval request of a parked control command also {@code separation} (neither its sender nor a
 * supervisor that asked for the approval may give it) and {@code rank} (a supervisor approves
 * another supervisor's command only if its scope strictly covers the sender's). Each failing rule
 * has its own message and counts in {@code yukta.input.denied{rule}}.
 *
 * <p>A request of risk {@code HIGH} can only be answered by a human; there is no setting that
 * changes this. A supervisor answers only when its profile says {@code canAnswer} and its {@code
 * maxRisk} reaches the request's risk.
 */
@SuppressWarnings("PMD.TooManyMethods")
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
  private static final String RULE_RANK = "rank";

  /** Finds the start payload of a run. */
  private final ExecutionControlStore runs;

  /** Counts denials. */
  private final MeterRegistry meters;

  /** Finds the parked command behind an approval request. */
  private final CommandProposals proposals;

  /**
   * Creates the authoriser for requests of plugins only.
   *
   * @param runs finds the start payload of a run
   * @param meters counts denials
   */
  public DefaultAnswerAuthorizer(final ExecutionControlStore runs, final MeterRegistry meters) {
    this(runs, meters, CommandProposals.NONE);
  }

  /**
   * Creates the authoriser.
   *
   * @param runs finds the start payload of a run
   * @param meters counts denials
   * @param proposals finds the parked command behind an approval request
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public DefaultAnswerAuthorizer(
      final ExecutionControlStore runs,
      final MeterRegistry meters,
      final CommandProposals proposals) {
    this.runs = runs;
    this.meters = meters;
    this.proposals = proposals;
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
    if (!matchesAny(actor, audience, request.tier())) {
      throw deny(request, RULE_AUDIENCE, actor, "is not in the audience of the request");
    }
    if (!matchesAny(actor, tierTargets, request.tier())) {
      throw deny(request, RULE_TIER, actor, "is not a target of tier '" + request.tier() + "'");
    }
    if (!context.escalation()) {
      checkRisk(request, actor);
    }
    checkScope(request, actor);
    checkSeparation(request, actor);
    if (!context.escalation()) {
      proposals.find(request.id()).ifPresent(proposal -> checkProposal(request, actor, proposal));
    }
  }

  /**
   * The rules of a parked command: whoever sent it and whoever asked for the approval may not
   * approve it ({@code separation}), and a supervisor approves a supervisor's command only if its
   * scope strictly covers the proposer's ({@code rank}). A proposer without an identity (no API
   * keys) is not separated, since every caller is the same person then.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  private void checkProposal(
      final InputRequest request, final ActorInfo actor, final CommandProposals.Proposal proposal) {
    final ActorInfo proposer = proposal.proposer();
    if (proposer != null && proposer.type() == actor.type() && proposer.id().equals(actor.id())) {
      throw deny(request, RULE_SEPARATION, actor, "sent the command, so it may not approve it");
    }
    if (actor.type() == ActorInfo.Type.SUPERVISOR && proposal.askedBy().contains(actor.id())) {
      throw deny(request, RULE_SEPARATION, actor, "asked for the approval, so it may not give it");
    }
    if (actor.type() == ActorInfo.Type.SUPERVISOR
        && proposer != null
        && proposer.type() == ActorInfo.Type.SUPERVISOR) {
      checkRank(request, actor, proposer);
    }
  }

  private void checkRank(
      final InputRequest request, final ActorInfo actor, final ActorInfo proposer) {
    final SupervisorScope proposed = proposer.scope();
    // the scope check ran before and refused a supervisor without a scope
    if (proposed == null || !Objects.requireNonNull(actor.scope()).strictlyCovers(proposed)) {
      throw deny(
          request,
          RULE_RANK,
          actor,
          "does not watch more than supervisor '" + proposer.id() + "', which sent the command");
    }
  }

  private static List<String> tierTargets(final InputRequest request) {
    return request.targets().isEmpty() ? DEFAULT_TARGETS : request.targets();
  }

  private void checkRisk(final InputRequest request, final ActorInfo actor) {
    final InputRisk risk = request.spec().risk();
    // an external actor only exists for a one-time token, which the token endpoint accepts for a
    // HIGH
    // request only in a tier of type HUMAN
    if (risk == InputRisk.HIGH
        && actor.type() != ActorInfo.Type.HUMAN
        && actor.type() != ActorInfo.Type.EXTERNAL) {
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
    final SupervisorScope scope = actor.scope();
    if (scope == null
        || !scope.matches(request.sessionId(), request.workflowId(), request.nodeId())) {
      throw deny(request, RULE_SCOPE, actor, "does not watch the run or node of the request");
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

  private static boolean matchesAny(
      final ActorInfo actor, final List<String> selectors, final String tier) {
    return selectors.stream().anyMatch(selector -> matches(actor, selector, tier));
  }

  private static boolean matches(final ActorInfo actor, final String selector, final String tier) {
    return switch (actor.type()) {
      case HUMAN ->
          ("key:" + actor.id()).equals(selector)
              || ("role:OPERATOR".equals(selector)
                  && (actor.role() == ApiRole.OPERATOR || actor.role() == ApiRole.ADMIN))
              || ("role:ADMIN".equals(selector) && actor.role() == ApiRole.ADMIN);
      case SUPERVISOR ->
          "supervisor:*".equals(selector) || ("supervisor:" + actor.id()).equals(selector);
      // a token stands for the tier it was delivered to, so it matches any external responder of it
      case EXTERNAL ->
          ("external:" + actor.id()).equals(selector)
              || (actor.id().equals(tier) && selector.startsWith("external:"));
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
